package almond.widgets

import almond.interpreter.api.{CommHandler, CommTarget}
import almond.widgets.internal.Registry

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal

/** Keeps track of the widgets of a kernel, and handles their communication with the front-end
  *
  * Implements the Jupyter widget messaging protocol, version 2.1, see
  * https://github.com/jupyter-widgets/ipywidgets/blob/8.1.7/packages/schema/messages.md
  *
  * @param echo
  *   whether to echo the changes made by front-ends back to all front-ends (`echo_update` messages
  *   of the protocol), so that several front-ends showing the same widgets stay in sync
  */
final class WidgetManager(commHandler: CommHandler, val echo: Boolean) {

  import WidgetManager._

  def this(commHandler: CommHandler) =
    this(commHandler, WidgetManager.defaultEcho)

  private val widgets0 = new ConcurrentHashMap[String, Widget]

  /** The open widget with id `id`, if any */
  def widget(id: String): Option[Widget] =
    Option(widgets0.get(id))

  /** All the open widgets */
  def widgets: Seq[Widget] = {
    val b  = Vector.newBuilder[Widget]
    val it = widgets0.values().iterator()
    while (it.hasNext)
      b += it.next()
    b.result()
  }

  private[widgets] def warn(message: String): Unit =
    System.err.println(s"Warning: $message")

  private val target: CommTarget =
    new CommTarget {
      def open(id: String, data: Array[Byte]): Unit =
        open(id, data, Nil)
      override def open(id: String, data: Array[Byte], buffers: Seq[Array[Byte]]): Unit =
        openedFromFrontend(id, data, buffers)
      def message(id: String, data: Array[Byte]): Unit =
        message(id, data, Nil)
      override def message(id: String, data: Array[Byte], buffers: Seq[Array[Byte]]): Unit =
        widget(id) match {
          case Some(w) =>
            try w.handleMessage(ujson.read(data), buffers)
            catch {
              case NonFatal(e) =>
                warn(s"Error processing message of widget $w: $e")
            }
          case None =>
            warn(s"Ignoring message for unknown widget $id")
        }
      def close(id: String, data: Array[Byte]): Unit =
        for (w <- Option(widgets0.remove(id)))
          w.closedFromFrontend()
    }

  private val controlTarget: CommTarget =
    new CommTarget {
      def open(id: String, data: Array[Byte]): Unit  = ()
      def close(id: String, data: Array[Byte]): Unit = ()
      def message(id: String, data: Array[Byte]): Unit = {
        val method =
          try ujson.read(data).objOpt.flatMap(_.get("method"))
          catch {
            case NonFatal(_) => None
          }
        method match {
          case Some(ujson.Str("request_states")) =>
            val states = widgets.filter(_.isOpen).map { w =>
              w.id -> Encoded.obj(
                "model_name"           -> Encoded(ujson.Str(w.modelName)),
                "model_module"         -> Encoded(ujson.Str(w.modelModule)),
                "model_module_version" -> Encoded(ujson.Str(w.modelModuleVersion)),
                "state"                -> w.encodedState
              )
            }
            val (states0, paths, buffers) = Encoded.toMessage(Encoded.obj(states: _*))
            val reply = ujson.Obj(
              "method"       -> ujson.Str("update_states"),
              "states"       -> states0,
              "buffer_paths" -> paths
            )
            commHandler.commMessage(id, bytes(reply), emptyObj, buffers)
          case _ =>
            warn(
              s"Ignoring unrecognized widget control message: ${new String(data, StandardCharsets.UTF_8)}"
            )
        }
      }
    }

  private[widgets] def registerTargets(): Unit = {
    commHandler.registerCommTarget(targetName, target)
    commHandler.registerCommTarget(controlTargetName, controlTarget)
  }

  private def openedFromFrontend(id: String, data: Array[Byte], buffers: Seq[Array[Byte]]): Unit =
    try {
      val (state, paths) = ujson.read(data) match {
        case obj: ujson.Obj =>
          val state = obj.value.get("state") match {
            case Some(s: ujson.Obj) => s
            case _                  => ujson.Obj()
          }
          val paths = obj.value.get("buffer_paths") match {
            case Some(ujson.Arr(p)) => p.toVector
            case _                  => Nil
          }
          (state, paths)
        case _ =>
          (ujson.Obj(), Nil)
      }
      val fullState = Encoded.fromMessage(state, paths, buffers)
      def str(key: String) =
        fullState.field(key).json.strOpt.getOrElse("")
      val w = WidgetManager.withCurrent(this) {
        Registry.create(str("_model_module"), str("_model_name")).getOrElse {
          new GenericWidget(
            str("_model_name"),
            str("_model_module"),
            str("_model_module_version"),
            fullState.field("_view_name").json.strOpt,
            str("_view_module"),
            str("_view_module_version")
          )
        }
      }
      w.openedFromFrontend(fullState, id)
      widgets0.put(w.id, w)
    }
    catch {
      case NonFatal(e) =>
        warn(s"Error creating widget opened by the front-end: $e")
    }

  private[widgets] def openComm(widget: Widget, data: ujson.Value, buffers: Seq[Array[Byte]])
    : Unit = {
    // registering things before sending comm_open, so that we don't miss the first messages
    widgets0.put(widget.id, widget)
    commHandler.registerCommId(widget.id, target)
    commHandler.commOpen(targetName, widget.id, bytes(data), versionMetadata, buffers)
  }

  private[widgets] def send(widget: Widget, data: ujson.Value, buffers: Seq[Array[Byte]]): Unit =
    commHandler.commMessage(widget.id, bytes(data), emptyObj, buffers)

  private[widgets] def closeComm(widget: Widget): Unit = {
    widgets0.remove(widget.id)
    commHandler.commClose(widget.id, emptyObj, emptyObj)
    commHandler.unregisterCommId(widget.id)
  }

  /** Closes all the open widgets */
  def closeAll(): Unit =
    widgets.foreach(_.close())
}

object WidgetManager {

  /** Name of the comm target of widgets */
  def targetName = "jupyter.widget"

  /** Name of the comm target front-ends use to get the state of all widgets at once */
  def controlTargetName = "jupyter.widget.control"

  /** Version of the Jupyter widget messaging protocol we implement */
  def protocolVersion = "2.1.0"

  private def bytes(json: ujson.Value): Array[Byte] =
    json.render().getBytes(StandardCharsets.UTF_8)
  private val emptyObj = "{}".getBytes(StandardCharsets.UTF_8)
  private def versionMetadata =
    bytes(ujson.Obj("version" -> ujson.Str(protocolVersion)))

  // Like ipywidgets, echoing is enabled by default, and can be disabled via
  // JUPYTER_WIDGETS_ECHO
  private[widgets] def defaultEcho: Boolean =
    Option(System.getenv("JUPYTER_WIDGETS_ECHO"))
      .map(_.trim.toLowerCase(Locale.ROOT))
      .forall(v => v != "0" && v != "false" && v != "no" && v != "off")

  @volatile private var current0 = Option.empty[WidgetManager]

  private val threadCurrent = new ThreadLocal[Option[WidgetManager]] {
    override def initialValue(): Option[WidgetManager] = None
  }

  /** The manager new widgets belong to, if any */
  def currentOpt: Option[WidgetManager] =
    threadCurrent.get().orElse(current0)

  /** The manager new widgets belong to
    *
    * @throws IllegalStateException
    *   if no manager is available (not running in a kernel supporting widgets)
    */
  def current: WidgetManager =
    currentOpt.getOrElse {
      throw new IllegalStateException(
        "No widget manager available (widgets can only be created from a kernel supporting them)"
      )
    }

  /** Runs `f`, with `manager` as current manager on the current thread */
  def withCurrent[T](manager: WidgetManager)(f: => T): T = {
    val previous = threadCurrent.get()
    threadCurrent.set(Some(manager))
    try f
    finally threadCurrent.set(previous)
  }

  /** Creates a manager, registers its comm targets, and makes it the default manager
    *
    * Called by kernels when they start, with their [[CommHandler]].
    */
  def install(commHandler: CommHandler): WidgetManager = {
    val manager = new WidgetManager(commHandler)
    manager.registerTargets()
    current0 = Some(manager)
    manager
  }
}
