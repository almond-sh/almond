package almond

import almond.channels.zeromq.ZeromqThreads
import almond.kernel.KernelThreads
import almond.util.ThreadUtil

import scala.concurrent.ExecutionContextExecutorService

final case class ScalaKernelThreads(
  interpreterEc: ExecutionContextExecutorService,
  updateBackgroundVariablesEc: ExecutionContextExecutorService,
  zeromqThreads: ZeromqThreads,
  kernelThreads: KernelThreads
) extends AutoCloseable {
  def close(): Unit = {
    interpreterEc.shutdown()
    updateBackgroundVariablesEc.shutdown()
    zeromqThreads.close()
    kernelThreads.attemptShutdown()
  }
}

object ScalaKernelThreads {
  def create(name: String): ScalaKernelThreads =
    ScalaKernelThreads(
      interpreterEc = ThreadUtil.singleThreadedExecutionContext(name + "-interpreter"),
      updateBackgroundVariablesEc =
        ThreadUtil.singleThreadedExecutionContext(name + "-update-background-variables"),
      zeromqThreads = ZeromqThreads.create(name),
      kernelThreads = KernelThreads.create(name)
    )
}
