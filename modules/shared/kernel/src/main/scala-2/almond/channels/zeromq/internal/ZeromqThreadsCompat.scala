package almond.channels.zeromq.internal

import almond.channels.zeromq.ZeromqThreads
import almond.channels.Channel
import scala.concurrent.ExecutionContext
import org.zeromq.ZMQ

trait ZeromqThreadsCompat

object ZeromqThreadsCompat {
  trait Companion {
    @deprecated("Binary-compatibility stub", "0.14.2")
    def unapply(threads: ZeromqThreads)
      : Option[(Channel => ExecutionContext, ExecutionContext, ExecutionContext, ZMQ.Context)] =
      Some((
        threads.channelEces,
        threads.selectorOpenCloseEces,
        threads.pollingEces,
        threads.context
      ))
  }
}
