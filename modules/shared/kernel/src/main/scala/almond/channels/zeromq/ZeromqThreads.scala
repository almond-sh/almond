package almond.channels.zeromq

import java.util.concurrent.Executors

import almond.channels.Channel
import almond.channels.zeromq.internal.ZeromqThreadsCompat
import almond.util.ThreadUtil.daemonThreadFactory
import org.zeromq.ZMQ

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.hashing.MurmurHash3

final class ZeromqThreads private (
  val channelEces: Channel => ExecutionContextExecutorService,
  val selectorOpenCloseEces: ExecutionContextExecutorService,
  private val pollingEcesOpt: Option[ExecutionContextExecutorService],
  val context: ZMQ.Context
) extends Product with Serializable with ZeromqThreadsCompat with AutoCloseable {

  def this(
    channelEces: Channel => ExecutionContextExecutorService,
    selectorOpenCloseEces: ExecutionContextExecutorService,
    context: ZMQ.Context
  ) =
    this(channelEces, selectorOpenCloseEces, None, context)

  @deprecated("pollingEces isn't used anymore, use the constructor not accepting it", "0.15.1")
  def this(
    channelEces: Channel => ExecutionContextExecutorService,
    selectorOpenCloseEces: ExecutionContextExecutorService,
    pollingEces: ExecutionContextExecutorService,
    context: ZMQ.Context
  ) =
    this(channelEces, selectorOpenCloseEces, Some(pollingEces), context)

  @deprecated("Use the constructor accepting ExecutionContextExecutorService-s instead", "0.14.2")
  def this(
    ecs: Channel => ExecutionContext,
    selectorOpenCloseEc: ExecutionContext,
    pollingEc: ExecutionContext,
    context: ZMQ.Context
  ) =
    this(
      c => ZeromqThreads.eces(ecs(c)),
      ZeromqThreads.eces(selectorOpenCloseEc),
      Some(ZeromqThreads.eces(pollingEc)),
      context
    )

  /** Not used by almond anymore
    *
    * If none was passed when creating this [[ZeromqThreads]], this is [[selectorOpenCloseEces]].
    */
  @deprecated("Not used anymore", "0.15.1")
  def pollingEces: ExecutionContextExecutorService =
    pollingEces0

  private def pollingEces0: ExecutionContextExecutorService =
    pollingEcesOpt.getOrElse(selectorOpenCloseEces)

  override def canEqual(that: Any): Boolean =
    that.isInstanceOf[ZeromqThreads]
  override def productArity: Int = 4
  override def productElement(n: Int): Any =
    n match {
      case 0 => channelEces
      case 1 => selectorOpenCloseEces
      case 2 => pollingEces0
      case 3 => context
      case _ => throw new NoSuchElementException
    }
  override def equals(that: Any): Boolean =
    canEqual(that) && {
      val other = that.asInstanceOf[ZeromqThreads]
      channelEces == other.channelEces &&
      selectorOpenCloseEces == other.selectorOpenCloseEces &&
      pollingEcesOpt == other.pollingEcesOpt &&
      context == other.context
    }
  override def hashCode(): Int =
    MurmurHash3.productHash(this)
  override def toString(): String =
    s"ZeromqThreads($channelEces, $selectorOpenCloseEces, $pollingEces0, $context)"

  @deprecated("Use with* methods instead", "0.14.2")
  def copy(
    ecs: Channel => ExecutionContext = c => channelEces(c),
    selectorOpenCloseEc: ExecutionContext = selectorOpenCloseEces,
    pollingEc: ExecutionContext = pollingEces0,
    context: ZMQ.Context = context
  ): ZeromqThreads =
    new ZeromqThreads(
      c => ZeromqThreads.eces(ecs(c)),
      ZeromqThreads.eces(selectorOpenCloseEc),
      Some(ZeromqThreads.eces(pollingEc)),
      context
    )

  def close(): Unit = {
    closePools()
    context.close()
  }

  private[almond] def closePools(): Unit = {
    for (c <- Channel.channels)
      channelEces(c).shutdown()
    selectorOpenCloseEces.shutdown()
    pollingEcesOpt.foreach(_.shutdown())
  }

  @deprecated("Use channelEces instead", "0.14.2")
  def ecs: Channel => ExecutionContext =
    c => channelEces(c)

  @deprecated("Use selectorOpenCloseEces instead", "0.14.2")
  def selectorOpenCloseEc: ExecutionContext = selectorOpenCloseEces

  @deprecated("Not used anymore", "0.14.2")
  def pollingEc: ExecutionContext = pollingEces0
}

object ZeromqThreads extends ZeromqThreadsCompat.Companion {

  private def eces(ec: ExecutionContext): ExecutionContextExecutorService =
    ec match {
      case eces0: ExecutionContextExecutorService => eces0
      case _ =>
        new ExecutionContextExecutorService {
          import java.util.{Collection, List => JList}
          import java.util.concurrent.{Callable, Future, TimeUnit}
          import scala.collection.JavaConverters._

          override def execute(runnable: Runnable): Unit     = ec.execute(runnable)
          override def reportFailure(cause: Throwable): Unit = ec.reportFailure(cause)

          override def shutdown(): Unit                                   = ()
          override def shutdownNow(): JList[Runnable]                     = Nil.asJava
          override def isShutdown(): Boolean                              = true
          override def isTerminated(): Boolean                            = true
          override def awaitTermination(x: Long, unit: TimeUnit): Boolean = true

          override def submit[T](x: Callable[T]): Future[T]                            = ???
          override def submit[T](x: Runnable, t: T): Future[T]                         = ???
          override def submit(x: Runnable): Future[_]                                  = ???
          override def invokeAll[T](x: Collection[_ <: Callable[T]]): JList[Future[T]] = ???
          override def invokeAll[T](
            x: Collection[_ <: Callable[T]],
            len: Long,
            unit: TimeUnit
          ): JList[Future[T]] = ???
          override def invokeAny[T](x: Collection[_ <: Callable[T]]): T = ???
          override def invokeAny[T](x: Collection[_ <: Callable[T]], len: Long, unit: TimeUnit): T =
            ???
        }
    }

  @deprecated("Use the override accepting ExecutionContextExecutorService-s instead", "0.14.2")
  def apply(
    ecs: Channel => ExecutionContext,
    selectorOpenCloseEc: ExecutionContext,
    pollingEc: ExecutionContext,
    context: ZMQ.Context
  ): ZeromqThreads =
    new ZeromqThreads(
      c => eces(ecs(c)),
      eces(selectorOpenCloseEc),
      Some(eces(pollingEc)),
      context
    )

  @deprecated("pollingEc isn't used anymore, use the override not accepting it", "0.15.1")
  def apply(
    ecs: Channel => ExecutionContextExecutorService,
    selectorOpenCloseEc: ExecutionContextExecutorService,
    pollingEc: ExecutionContextExecutorService,
    context: ZMQ.Context
  ): ZeromqThreads =
    new ZeromqThreads(
      ecs,
      selectorOpenCloseEc,
      Some(pollingEc),
      context
    )

  def apply(
    ecs: Channel => ExecutionContextExecutorService,
    selectorOpenCloseEc: ExecutionContextExecutorService,
    context: ZMQ.Context
  ): ZeromqThreads =
    new ZeromqThreads(
      ecs,
      selectorOpenCloseEc,
      context
    )

  def create(name: String, zmqIOThreads: Int = 4): ZeromqThreads = {

    val ctx = ZMQ.context(zmqIOThreads)
    val zeromqOpenCloseEc = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(
      2,
      daemonThreadFactory(s"$name-zeromq-open-close")
    ))
    val zeromqChannelEcs = Channel.channels.map(c =>
      c -> ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor(daemonThreadFactory(s"$name-zeromq-$c"))
      )
    ).toMap

    ZeromqThreads(
      zeromqChannelEcs,
      zeromqOpenCloseEc,
      ctx
    )
  }

}
