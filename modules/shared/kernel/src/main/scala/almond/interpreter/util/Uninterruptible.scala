package almond.interpreter.util

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object Uninterruptible {

  /** Runs `io` and waits for its result, like `io.unsafeRunSync()`, but without letting thread
    * interruptions break the wait
    *
    * Meant to be used from threads running user code, to publish what it outputs. When users
    * interrupt a cell, with `Thread.interrupt`, user code that doesn't check for interruptions can
    * keep running, and output things. If the interrupt flag of the thread is set, `unsafeRunSync`
    * gives up waiting for the result of `io`, and throws a `NoSuchElementException`, even though
    * `io` might still be running.
    *
    * Here, we wait for `io` to complete regardless of interruptions, and set the interrupt flag of
    * the thread again if needed afterwards, so that user code can still handle interruptions.
    */
  def unsafeRunSync[T](io: IO[T])(implicit runtime: IORuntime): T = {
    val f           = io.unsafeToFuture()
    var interrupted = false
    try
      while (!f.isCompleted)
        try Await.ready(f, Duration.Inf)
        catch {
          case _: InterruptedException =>
            interrupted = true
        }
    finally
      if (interrupted)
        Thread.currentThread().interrupt()
    f.value.get.get
  }
}
