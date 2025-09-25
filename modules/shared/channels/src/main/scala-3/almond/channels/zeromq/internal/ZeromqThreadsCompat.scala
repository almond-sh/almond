package almond.channels.zeromq.internal

import almond.channels.zeromq.ZeromqThreads
import almond.channels.Channel
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import org.zeromq.ZMQ
import scala.deriving.Mirror

trait ZeromqThreadsCompat {

  def channelEces: Channel => ExecutionContextExecutorService
  def selectorOpenCloseEces: ExecutionContextExecutorService
  def pollingEces: ExecutionContextExecutorService
  def context: ZMQ.Context

  def _1: Channel => ExecutionContext = channelEces
  def _2: ExecutionContext            = selectorOpenCloseEces
  def _3: ExecutionContext            = pollingEces
  def _4: ZMQ.Context                 = context
}

object ZeromqThreadsCompat {
  trait Companion extends Mirror.Product {
    type MirroredMonoType = ZeromqThreads

    @deprecated("Binary-compatibility stub", "0.14.2")
    def unapply(threads: ZeromqThreads): ZeromqThreads =
      threads
    @deprecated("Prefer using the with* methods", "0.14.2")
    def fromProduct(product: Product): ZeromqThreads =
      ZeromqThreads(
        product.productElement(0).asInstanceOf[Channel => ExecutionContext],
        product.productElement(1).asInstanceOf[ExecutionContext],
        product.productElement(2).asInstanceOf[ExecutionContext],
        product.productElement(3).asInstanceOf[ZMQ.Context]
      )
  }
}
