package almond.kernel

import java.util.concurrent.Executors

import almond.util.ThreadUtil.{
  attemptShutdownExecutionContext,
  daemonThreadFactory,
  sequentialExecutionContext
}

import scala.concurrent.ExecutionContext

final case class KernelThreads(
  queueEc: ExecutionContext,
  futureEc: ExecutionContext,
  scheduleEc: ExecutionContext,
  commEc: ExecutionContext
) {
  def attemptShutdown(): Unit =
    Seq(queueEc, futureEc, scheduleEc, commEc)
      .distinct
      .foreach { ec =>
        if (!attemptShutdownExecutionContext(ec))
          println(s"Don't know how to shutdown $ec")
      }
}

object KernelThreads {
  def create(name: String): KernelThreads = {

    val dummyStuffEc = ExecutionContext.fromExecutorService(
      Executors.newSingleThreadExecutor(daemonThreadFactory(s"$name-dummy-stuff"))
    )

    KernelThreads(
      sequentialExecutionContext(),
      dummyStuffEc,
      dummyStuffEc,
      ExecutionContext.fromExecutorService(
        Executors.newFixedThreadPool(2, daemonThreadFactory(s"$name-comm"))
      )
    )
  }
}
