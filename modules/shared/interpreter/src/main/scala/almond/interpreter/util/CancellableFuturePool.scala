package almond.interpreter.util

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.{Executors, ThreadFactory}

import almond.logger.LoggerContext

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

final class CancellableFuturePool(
  logCtx: LoggerContext
) {

  private val log = logCtx(getClass)

  private val pool = Executors.newCachedThreadPool(
    // from scalaz.concurrent.Strategy.DefaultDaemonThreadFactory
    new ThreadFactory {
      val defaultThreadFactory = Executors.defaultThreadFactory()
      def newThread(r: Runnable) = {
        val t = defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        t.setUncaughtExceptionHandler(
          new UncaughtExceptionHandler {
            def uncaughtException(t: Thread, e: Throwable) =
              log.warn(s"Uncaught exception in thread $t", e)
          }
        )
        t
      }
    }
  )

  def future[T](result: => T): Future[T] = {

    val p = Promise[T]()

    pool.submit(
      new Runnable {
        def run() =
          p.complete {
            try Success(result)
            catch {
              case NonFatal(e) =>
                Failure(e)
            }
          }
      }
    )

    p.future
  }

  def cancellableFuture[T](result: T): CancellableFuture[T] = {

    @volatile var completionThreadOpt = Option.empty[Thread]

    def result0(): T = {
      completionThreadOpt = Some(Thread.currentThread())
      try result
      finally
        completionThreadOpt = None
    }

    def cancel(): Unit =
      for (t <- completionThreadOpt)
        t.stop()

    CancellableFuture(future(result0()), () => cancel())
  }

  def shutdown(): Unit =
    pool.shutdown()

}
