package almond.util

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.control.NonFatal

object ThreadUtil {

  // From https://github.com/functional-streams-for-scala/fs2/blob/d47f903bc6bbcdd5d8bc6d573bc7cfd956f0cbb6/core/jvm/src/main/scala/fs2/Strategy.scala#L19-L41
  /** A `ThreadFactory` which creates daemon threads, using the given name. */
  def daemonThreadFactory(threadName: String, exitJvmOnFatalError: Boolean = true): ThreadFactory =
    new ThreadFactory {
      val defaultThreadFactory = Executors.defaultThreadFactory()
      val idx                  = new AtomicInteger(0)
      def newThread(r: Runnable) = {
        val t = defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        t.setName(s"$threadName-${idx.incrementAndGet()}")
        t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
          def uncaughtException(t: Thread, e: Throwable): Unit = {
            System.err.println(s"------------ UNHANDLED EXCEPTION ---------- (${t.getName})")
            e.printStackTrace(System.err)
            if (exitJvmOnFatalError)
              e match {
                case NonFatal(_) => ()
                case fatal       => System.exit(-1)
              }
          }
        })
        t
      }
    }

  def sequentialExecutionContext(): ExecutionContext =
    new SequentialExecutionContext

  def singleThreadedExecutionContext(threadName: String): ExecutionContext =
    ExecutionContext.fromExecutorService(
      Executors.newSingleThreadExecutor(daemonThreadFactory(threadName))
    )

  def attemptShutdownExecutionContext(ec: ExecutionContext): Boolean =
    ec match {
      case _: SequentialExecutionContext =>
        true
      case es: ExecutionContextExecutorService =>
        es.shutdown()
        true
      case _ =>
        false
    }

}
