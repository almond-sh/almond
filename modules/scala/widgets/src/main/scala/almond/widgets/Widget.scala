package almond.widgets

import almond.display.Display
import almond.interpreter.api.DisplayData

import java.util.{Locale, UUID}
import java.util.concurrent.atomic.AtomicLong

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

/** Base class of Jupyter widgets
  *
  * A widget is made of a state (a set of properties, see [[Prop]]), that is synced with its
  * counterpart in the front-end (the widget "model"). Widgets with a view can be displayed (see
  * [[DOMWidget]]).
  *
  * Widgets are opened lazily: their counterpart in the front-end gets created when they're
  * displayed, when a widget referencing them is opened, or when [[open]] is called. Their
  * properties can be changed beforehand, without sending any message to the front-end.
  *
  * Callbacks (property observers, click handlers, …) are called from a thread processing messages
  * coming from the front-end, that can run while cells are running.
  */
abstract class Widget {

  /** Name of the JavaScript model class of this widget */
  def modelName: String

  /** JavaScript module providing the model of this widget */
  def modelModule: String

  /** Semver range for the version of [[modelModule]] */
  def modelModuleVersion: String

  /** Name of the JavaScript view class of this widget, if it has one */
  def viewName: Option[String]

  /** JavaScript module providing the view of this widget */
  def viewModule: String

  /** Semver range for the version of [[viewModule]] */
  def viewModuleVersion: String

  /** The manager this widget belongs to */
  final val manager: WidgetManager = WidgetManager.current

  // only changed for widgets created by the front-end, before they're registered anywhere
  @volatile private var id0: String = Widget.newId()

  /** Identifier of this widget, that's also the id of its comm */
  final def id: String = id0

  private val lock = new Object

  private var state0      = ListMap.empty[String, Encoded]
  private var defaults    = Map.empty[String, Encoded]
  private var references0 = Map.empty[String, Seq[Widget]]
  private var noEcho      = Set.empty[String]
  private var observers   = Map.empty[String, Vector[Widget.Observer]]
  private var msgHandlers = Vector.empty[Widget.MessageHandler]
  private var opened      = false
  private var closed      = false
  private var holdCount   = 0
  private var held        = Vector.empty[String]

  /** Declares a property, part of the state of this widget
    *
    * @param name
    *   name of the property in the widget state
    * @param default
    *   initial value of the property
    * @param echo
    *   whether changes of this property coming from a front-end are echoed back to all front-ends
    */
  final protected def prop[T](name: String, default: T, echo: Boolean = true)(implicit
    codec: Codec[T]
  ): StateProp[T] = {
    lock.synchronized {
      val encoded = codec.encode(default)
      state0 = Widget.updated(state0, name, encoded)
      defaults = defaults.updated(name, encoded)
      references0 = references0.updated(name, codec.references(default))
      if (!echo)
        noEcho = noEcho + name
    }
    new StateProp(this, name, codec)
  }

  /** Declares a property computed from other properties */
  final protected def derivedProp[T](dependencies: Prop[_]*)(get: => T)(set: T => Unit)
    : DerivedProp[T] =
    new DerivedProp(this, () => get, set, dependencies)

  private def modelIdentity: ListMap[String, Encoded] =
    ListMap(
      "_model_name"           -> Encoded(ujson.Str(modelName)),
      "_model_module"         -> Encoded(ujson.Str(modelModule)),
      "_model_module_version" -> Encoded(ujson.Str(modelModuleVersion)),
      "_view_name"            -> viewName.fold(Encoded.Null)(name => Encoded(ujson.Str(name))),
      "_view_module"          -> Encoded(ujson.Str(viewModule)),
      "_view_module_version"  -> Encoded(ujson.Str(viewModuleVersion))
    )

  /** The full state of this widget, as sent to the front-end */
  final def encodedState: Encoded =
    lock.synchronized {
      Encoded.obj((modelIdentity ++ state0).toSeq: _*)
    }

  /** The full state of this widget, as sent to the front-end, without its binary values (see
    * [[buffers]])
    */
  final def state: ujson.Obj =
    // copying it, so that changing it doesn't change the state of this widget
    ujson.copy(Encoded.toMessage(encodedState)._1) match {
      case obj: ujson.Obj => obj
      case _              => ujson.Obj()
    }

  /** The binary values of the state of this widget, along with their paths in [[state]] */
  final def buffers: Seq[Encoded.Buffer] =
    encodedState.buffers

  /** The widgets referenced by a field of the state of this widget */
  private[widgets] def referencedWidgets(name: String): Seq[Widget] =
    lock.synchronized {
      references0.getOrElse(name, Nil)
    }

  /** The value of a field of the state of this widget (`null` if it's not set) */
  final def stateValue(name: String): Encoded =
    lock.synchronized {
      state0.getOrElse(name, Encoded.Null)
    }

  /** Changes a field of the state of this widget, sending it to the front-end if needed
    *
    * Prefer changing properties via the [[Prop]] instances of the widget.
    *
    * @param references
    *   widgets referenced by the new value, that need to be opened before it's sent to the
    *   front-end
    */
  final def setStateValue(
    name: String,
    value: Encoded,
    references: Seq[Widget] = Nil
  ): Unit = {
    val formerOpt = lock.synchronized {
      val former = state0.getOrElse(name, Encoded.Null)
      if (former == value && state0.contains(name)) None
      else {
        val formerRefs = references0.getOrElse(name, Nil)
        state0 = Widget.updated(state0, name, value)
        references0 = references0.updated(name, references)
        if (opened && !closed)
          if (holdCount > 0) {
            if (!held.contains(name))
              held = held :+ name
          }
          else {
            references.foreach(_.open())
            sendState(ListMap(name -> value), "update")
          }
        Some((former, formerRefs))
      }
    }
    for ((former, formerRefs) <- formerOpt)
      notifyObservers(name, former, value, formerRefs)
  }

  /** Runs `f`, sending the changes it makes to the state of this widget in a single message
    * afterwards
    */
  final def holdSync[T](f: => T): T = {
    lock.synchronized {
      holdCount += 1
    }
    try f
    finally
      lock.synchronized {
        holdCount -= 1
        if (holdCount == 0 && held.nonEmpty) {
          val names = held
          held = Vector.empty
          if (opened && !closed) {
            names.flatMap(references0.getOrElse(_, Nil)).foreach(_.open())
            sendState(ListMap(names.map(name => name -> state0(name)): _*), "update")
          }
        }
      }
  }

  // must be called with lock held
  private def sendState(fields: ListMap[String, Encoded], method: String): Unit = {
    val (state, paths, buffers) = Encoded.toMessage(Encoded.obj(fields.toSeq: _*))
    manager.send(
      this,
      ujson.Obj(
        "method"       -> ujson.Str(method),
        "state"        -> state,
        "buffer_paths" -> paths
      ),
      buffers
    )
  }

  /** Creates the counterpart of this widget in the front-end, if it's not been created yet
    *
    * This also opens the widgets referenced by this one.
    */
  final def open(): this.type = {
    lock.synchronized {
      if (!opened && !closed) {
        opened = true
        references0.valuesIterator.flatten.foreach(_.open())
        val (state0, paths, buffers) = Encoded.toMessage(encodedState)
        manager.openComm(
          this,
          ujson.Obj("state" -> state0, "buffer_paths" -> paths),
          buffers
        )
      }
    }
    this
  }

  final def isOpen: Boolean =
    lock.synchronized(opened && !closed)

  final def isClosed: Boolean =
    lock.synchronized(closed)

  /** Closes this widget: removes it from the front-end, and stops syncing its state */
  final def close(): Unit = {
    val wasOpen = lock.synchronized {
      val wasOpen = opened && !closed
      closed = true
      wasOpen
    }
    if (wasOpen)
      manager.closeComm(this)
  }

  /** Sends a custom message to the front-end counterpart of this widget */
  final def send(content: ujson.Value, buffers: Seq[Array[Byte]] = Nil): Unit = {
    open()
    lock.synchronized {
      if (!closed)
        manager.send(
          this,
          ujson.Obj("method" -> ujson.Str("custom"), "content" -> content),
          buffers
        )
    }
  }

  /** Calls `f` with the custom messages sent by the front-end counterpart of this widget */
  final def onMessage(f: (ujson.Value, Seq[Array[Byte]]) => Unit): Subscription = {
    val handler = new Widget.MessageHandler(f)
    lock.synchronized {
      msgHandlers = msgHandlers :+ handler
    }
    Subscription { () =>
      lock.synchronized {
        msgHandlers = msgHandlers.filter(_ ne handler)
      }
    }
  }

  /** Calls `f` with the former and new values of a field of the state, when it changes */
  final def observeKey(name: String)(f: (Encoded, Encoded) => Unit): Subscription = {
    val observer = new Widget.Observer(f)
    lock.synchronized {
      observers = observers.updated(name, observers.getOrElse(name, Vector.empty) :+ observer)
    }
    Subscription { () =>
      lock.synchronized {
        val remaining = observers.getOrElse(name, Vector.empty).filter(_ ne observer)
        observers = observers.updated(name, remaining)
      }
    }
  }

  /** @param formerRefs
    *   widgets referenced by the former value, that observers may need to decode it
    */
  private def notifyObservers(
    name: String,
    former: Encoded,
    value: Encoded,
    formerRefs: Seq[Widget]
  ): Unit = {
    val observers0 = lock.synchronized(observers.getOrElse(name, Vector.empty))
    if (observers0.nonEmpty)
      Codec.withResolver(id => formerRefs.find(_.id == id)) {
        for (observer <- observers0)
          Widget.runCallback(s"observer of $name of $this")(observer.f(former, value))
      }
  }

  // Front-end side

  private[widgets] def openedFromFrontend(state: Encoded, commId: String): Unit = {
    lock.synchronized {
      id0 = commId
      opened = true
      for (name <- state.fieldNames if !name.startsWith("_"))
        state0 = Widget.updated(state0, name, state.field(name))
    }
  }

  private[widgets] def closedFromFrontend(): Unit =
    lock.synchronized {
      closed = true
    }

  private[widgets] def handleMessage(data: ujson.Value, buffers: Seq[Array[Byte]]): Unit =
    data match {
      case obj: ujson.Obj =>
        obj.value.get("method") match {
          case Some(ujson.Str("update")) =>
            val paths = obj.value.get("buffer_paths") match {
              case Some(ujson.Arr(paths)) => paths.toVector
              case _                      => Nil
            }
            obj.value.get("state") match {
              case Some(state: ujson.Obj) =>
                val update = Encoded.fromMessage(state, paths, buffers)
                updateFromFrontend(ListMap(update.fieldNames.map(n => n -> update.field(n)): _*))
              case _ =>
            }
          case Some(ujson.Str("request_state")) =>
            lock.synchronized {
              if (opened && !closed) {
                val state = encodedState
                sendState(ListMap(state.fieldNames.map(n => n -> state.field(n)): _*), "update")
              }
            }
          case Some(ujson.Str("custom")) =>
            val content  = obj.value.getOrElse("content", ujson.Null)
            val handlers = lock.synchronized(msgHandlers)
            for (handler <- handlers)
              Widget.runCallback(s"message handler of $this")(handler.f(content, buffers))
          case _ =>
            manager.warn(s"Ignoring unrecognized message for $this: ${data.render()}")
        }
      case _ =>
        manager.warn(s"Ignoring unrecognized message for $this: ${data.render()}")
    }

  private def updateFromFrontend(update: ListMap[String, Encoded]): Unit = {
    val changes = lock.synchronized {
      if (manager.echo) {
        val echoed = update.filter { case (name, _) => !noEcho.contains(name) }
        if (echoed.nonEmpty && opened && !closed)
          sendState(echoed, "echo_update")
      }
      update.toVector.flatMap {
        case (name, value) =>
          val former = state0.getOrElse(name, Encoded.Null)
          if (former == value) Nil
          else {
            val formerRefs = references0.getOrElse(name, Nil)
            state0 = Widget.updated(state0, name, value)
            // widgets referenced by front-ends are open, and can be found via the manager
            references0 = references0 - name
            Seq((name, former, value, formerRefs))
          }
      }
    }
    for ((name, former, value, formerRefs) <- changes)
      notifyObservers(name, former, value, formerRefs)
  }

  /** Like in ipywidgets, the class of the widget, along with the fields of its state that don't
    * have their default value (except references to other widgets, and binary data)
    */
  override def toString: String = {
    val fields = lock.synchronized {
      state0.toVector.filter {
        case (name, value) =>
          !name.startsWith("_") &&
          defaults.get(name).forall(_ != value) &&
          references0.getOrElse(name, Nil).isEmpty &&
          value.buffers.isEmpty
      }
    }
    val maxLength = 40
    fields
      .map {
        case (name, value) =>
          val rendered = value.json.render()
          val truncated =
            if (rendered.length <= maxLength) rendered
            else rendered.take(maxLength - 1) + "…"
          s"$name=$truncated"
      }
      .mkString(s"${getClass.getSimpleName}(", ", ", ")")
  }
}

object Widget {

  // ListMap#updated keeps the position of existing keys in Scala 2.13 and 3, but moves them
  // last in Scala 2.12: this keeps the order of the state (that of the property declarations)
  // the same everywhere
  private def updated(
    map: ListMap[String, Encoded],
    key: String,
    value: Encoded
  ): ListMap[String, Encoded] =
    if (map.contains(key)) map.map { case (k, v) => if (k == key) (k, value) else (k, v) }
    else map.updated(key, value)

  // Like the ids of updatable displays (see JupyterApi), ids are random unless
  // ALMOND_USE_RANDOM_IDS is false, so that tests get the same ids every time
  private lazy val useRandomIds: Boolean =
    Option(System.getenv("ALMOND_USE_RANDOM_IDS"))
      .orElse(sys.props.get("almond.ids.random"))
      .forall(s => s == "1" || s.toLowerCase(Locale.ROOT) == "true")
  private val idCounter = new AtomicLong

  private def newId(): String =
    if (useRandomIds) UUID.randomUUID().toString.replace("-", "")
    else "%032x".format(idCounter.incrementAndGet())

  private final class Observer(val f: (Encoded, Encoded) => Unit)
  private final class MessageHandler(val f: (ujson.Value, Seq[Array[Byte]]) => Unit)

  private[widgets] def runCallback(what: => String)(f: => Unit): Unit =
    try f
    catch {
      case NonFatal(e) =>
        System.err.println(s"Exception in $what")
        e.printStackTrace(System.err)
    }

  /** MIME type of the data that displays widgets in the front-end */
  def viewMimeType: String = "application/vnd.jupyter.widget-view+json"
}

/** Widgets with a view, that can be displayed */
abstract class DOMWidget extends Widget with Display {

  def layout: StateProp[Layout]

  def data(): Map[String, String] =
    Map(DisplayData.ContentType.text -> toString)

  override def displayData(): DisplayData = {
    open()
    val viewData = ujson.Obj(
      "version_major" -> ujson.Num(2),
      "version_minor" -> ujson.Num(0),
      "model_id"      -> ujson.Str(id)
    )
    DisplayData()
      .add(DisplayData.ContentType.text, toString)
      .addStringifiedJson(Widget.viewMimeType, viewData.render())
  }
}

/** Widgets with a value */
trait ValueWidget[T] extends Widget {
  def value: Prop[T]
}

/** Widgets with a description, shown next to them */
trait DescriptionWidget extends Widget {
  def description: StateProp[String]
}

/** Widgets containing other widgets */
trait ContainerWidget extends Widget {
  def children: StateProp[Seq[Widget]]
}
