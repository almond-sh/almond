package almond.widgets

/** Something registered for later, that can be cancelled (callbacks, links, …) */
trait Subscription {
  def cancel(): Unit
}

object Subscription {
  def apply(onCancel: () => Unit): Subscription =
    new Subscription {
      def cancel(): Unit = onCancel()
      override def toString: String =
        "Subscription"
    }
  def combine(subscriptions: Seq[Subscription]): Subscription =
    apply(() => subscriptions.foreach(_.cancel()))
}

/** A property of a widget
  *
  * Get its value with `prop()`, change it with `prop() = newValue` (or `prop := newValue`), and get
  * notified of changes, either made from the kernel or from the front-end, with `prop.observe`.
  */
abstract class Prop[T] {

  /** The widget this property belongs to */
  def widget: Widget

  /** The current value of this property */
  def apply(): T

  /** Changes the value of this property, and sends it to the front-end */
  def update(value: T): Unit

  /** Calls `f` with the former and new values of this property, each time it changes */
  def observeChange(f: (T, T) => Unit): Subscription

  final def get: T                  = apply()
  final def set(value: T): Unit     = update(value)
  final def :=(value: T): Unit      = update(value)
  final def modify(f: T => T): Unit = update(f(apply()))
  final def observe(f: T => Unit): Subscription =
    observeChange((_, newValue) => f(newValue))

  override def toString: String =
    s"Prop(${apply()})"
}

/** A property that is part of the state of its widget, synced with the front-end
  *
  * @param name
  *   name of the property in the widget state
  */
final class StateProp[T] private[widgets] (
  val widget: Widget,
  val name: String,
  val codec: Codec[T]
) extends Prop[T] {
  // references to other widgets are looked up among the widgets referenced by this property,
  // that may not be open yet, then in the manager of our widget
  private def decode(value: Encoded): T =
    Codec.withResolver { id =>
      widget.referencedWidgets(name).find(_.id == id).orElse(widget.manager.widget(id))
    } {
      codec.decode(value)
    }

  def apply(): T =
    decode(widget.stateValue(name))
  def update(value: T): Unit =
    widget.setStateValue(name, codec.encode(value), codec.references(value))
  def observeChange(f: (T, T) => Unit): Subscription =
    widget.observeKey(name) { (oldValue, newValue) =>
      f(decode(oldValue), decode(newValue))
    }

  override def toString: String =
    s"StateProp($name, ${apply()})"
}

/** A property computed from other properties, that isn't directly synced with the front-end */
final class DerivedProp[T] private[widgets] (
  val widget: Widget,
  get0: () => T,
  set0: T => Unit,
  dependencies: Seq[Prop[_]]
) extends Prop[T] {
  def apply(): T =
    get0()
  def update(value: T): Unit =
    set0(value)
  def observeChange(f: (T, T) => Unit): Subscription = {
    val lock   = new Object
    var former = apply()
    val subscriptions = dependencies.map { dep =>
      dep.observeChange { (_, _) =>
        val (before, after, changed) = lock.synchronized {
          val before = former
          val after  = apply()
          former = after
          (before, after, before != after)
        }
        if (changed)
          f(before, after)
      }
    }
    Subscription.combine(subscriptions)
  }
}
