package almond.channels.zeromq

import java.util.concurrent.Executors

import almond.channels.Channel
import almond.util.ThreadUtil.daemonThreadFactory
import org.zeromq.ZMQ

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

final case class ZeromqThreads(
  ecs: Channel => ExecutionContextExecutorService,
  selectorOpenCloseEc: ExecutionContextExecutorService,
  pollingEc: ExecutionContextExecutorService,
  context: ZMQ.Context
) extends AutoCloseable {
  def close(): Unit = {
    for (c <- Channel.channels)
      ecs(c).shutdown()
    selectorOpenCloseEc.shutdown()
    pollingEc.shutdown()
    context.close()
  }
}

object ZeromqThreads {

  def create(name: String, zmqIOThreads: Int = 4): ZeromqThreads = {

    val ctx = ZMQ.context(zmqIOThreads)
    val zeromqOpenCloseEc = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(
      2,
      daemonThreadFactory(s"$name-zeromq-open-close")
    ))
    val zeromqPollingEc = ExecutionContext.fromExecutorService(
      Executors.newSingleThreadExecutor(daemonThreadFactory(s"$name-zeromq-polling"))
    )
    val zeromqChannelEcs = Channel.channels.map(c =>
      c -> ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor(daemonThreadFactory(s"$name-zeromq-$c"))
      )
    ).toMap

    ZeromqThreads(
      zeromqChannelEcs,
      zeromqOpenCloseEc,
      zeromqPollingEc,
      ctx
    )
  }

}
