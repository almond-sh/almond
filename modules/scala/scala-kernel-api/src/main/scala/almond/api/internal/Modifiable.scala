package almond.api.internal

/** Wraps a var, allowing to notify some listeners upon change.
  *
  * Used by the auto-updated var-s mechanism in particular.
  *
  * Registering listeners and changing the value can be done from any thread. Like for a plain var,
  * concurrent read-modify-write operations (like `+= 1`) aren't atomic though.
  *
  * Listeners are called from the thread that changes the value, after the new value has been stored
  * (so that listeners reading `value` see the new value). They should be cheap, as they're called
  * upon every change.
  */
final class Modifiable[T](initialValue: T) {
  @volatile private var value0: T = initialValue
  @volatile private var listeners = List.empty[T => Unit]
  def onChange: (T => Unit) => Unit = { f =>
    synchronized {
      listeners = f :: listeners
    }
  }
  def value: T = value0
  def value_=(v: T): Unit = {
    value0 = v
    listeners.foreach(_(v))
  }
}
