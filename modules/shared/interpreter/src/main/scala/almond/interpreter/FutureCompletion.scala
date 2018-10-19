package almond.interpreter

import scala.concurrent.Future

final case class FutureCompletion(
  future: Future[Completion],
  cancel: () => Unit
)
