package almond

import almond.channels.zeromq.ZeromqThreads
import almond.kernel.KernelThreads
import almond.util.ThreadUtil

import scala.concurrent.ExecutionContextExecutorService

final case class ScalaKernelThreads(
  interpreterEc: ExecutionContextExecutorService,
  updateBackgroundVariablesEc: ExecutionContextExecutorService,
  cancellableEc: ExecutionContextExecutorService,
  zeromqThreads: ZeromqThreads,
  kernelThreads: KernelThreads
) extends AutoCloseable {
  def close(): Unit = {
    interpreterEc.shutdown()
    updateBackgroundVariablesEc.shutdown()
    cancellableEc.shutdown()
    zeromqThreads.close()
    kernelThreads.attemptShutdown()
  }
}

object ScalaKernelThreads {
  def create(name: String): ScalaKernelThreads =
    ScalaKernelThreads(
      interpreterEc =
        ThreadUtil.singleThreadedExecutionContextExecutorService(name + "-interpreter"),
      updateBackgroundVariablesEc =
        ThreadUtil.singleThreadedExecutionContextExecutorService(
          name + "-update-background-variables"
        ),
      cancellableEc =
        ThreadUtil.singleThreadedExecutionContextExecutorService(name + "-cancellables"),
      zeromqThreads = ZeromqThreads.create(name),
      kernelThreads = KernelThreads.create(name)
    )
}
