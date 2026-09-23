package almond.interpreter.util

import scala.concurrent.Future

final case class CancellableFuture[+T](
  future: Future[T],
  cancel: () => Unit
)
