package almond.channels.zeromq

import java.util.concurrent.Executors

import almond.channels.Channel
import almond.util.ThreadUtil.daemonThreadFactory
import org.zeromq.ZMQ

import scala.concurrent.ExecutionContext

final case class ZeromqThreads(
  ecs: Channel => ExecutionContext,
  selectorOpenCloseEc: ExecutionContext,
  pollingEc: ExecutionContext,
  context: ZMQ.Context
)

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
