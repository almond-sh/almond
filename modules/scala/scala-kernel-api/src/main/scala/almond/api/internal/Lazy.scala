package almond.api.internal

final class Lazy[T](private var compute: () => T) {
  private var listeners = List.empty[Either[Throwable, T] => Unit]
  def onChange: (Either[Throwable, T] => Unit) => Unit = { f =>
    listeners = f :: listeners
  }
  lazy val value: T = {
    val e =
      try Right(compute())
      catch {
        case ex: Throwable => // catch less things here?
          Left(ex)
      }
    listeners.foreach(_(e))
    e match {
      case Right(t) =>
        compute = null
        t
      case Left(ex) =>
        throw ex
    }
  }
}
