package almond.api.internal

/** Wraps a var, allowing to notify some listeners upon change.
  *
  * Used by the auto-updated var-s mechanism in particular.
  *
  * Not thread-safe for now.
  */
final class Modifiable[T](private var value0: T) {
  private var listeners = List.empty[T => Unit]
  def onChange: (T => Unit) => Unit = { f =>
    listeners = f :: listeners
  }
  def value: T = value0
  def value_=(v: T): Unit = {
    listeners.foreach(_(v))
    value0 = v
  }
}
