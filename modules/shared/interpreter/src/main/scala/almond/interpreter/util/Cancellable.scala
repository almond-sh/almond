package almond.interpreter.util

import cats.effect.IO

import scala.util.{Failure, Success}

/** Handles cancellation of prior running requests, in case a new one is incoming.
  *
  * This only applies if `asyncOpt` returns non-empty results, meaning [[B]] is asynchronously
  * calculated, and that this calculation can be stopped. If a new request arrives,
  * [[CancellableFuture.cancel]] is called on previous requests that are still running.
  */
final class Cancellable[A, B](
  sync: A => IO[B],
  asyncOpt: A => Option[CancellableFuture[B]]
) {

  private var runningCompletionOpt  = Option.empty[CancellableFuture[B]]
  private val runningCompletionLock = new Object

  def run(a: A): IO[B] = {

    val ioOrFutureCompletion =
      runningCompletionLock.synchronized {

        for (c <- runningCompletionOpt) {
          c.cancel()
          runningCompletionOpt = None
        }

        asyncOpt(a) match {
          case None =>
            Left(sync(a))
          case Some(f) =>
            runningCompletionOpt = Some(f)
            Right(f)
        }
      }

    ioOrFutureCompletion match {
      case Left(io) => io
      case Right(f) =>
        IO.async[B] { cb =>
          import scala.concurrent.ExecutionContext.Implicits.global // meh
          f.future.onComplete { res =>
            runningCompletionLock.synchronized {
              runningCompletionOpt = runningCompletionOpt.filter(_ != f)
            }
            res match {
              case Success(c) =>
                cb(Right(c))
              case Failure(e) =>
                cb(Left(e))
            }
          }
          IO.none
        }
    }
  }

}
