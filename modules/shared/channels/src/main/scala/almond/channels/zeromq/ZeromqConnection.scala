package almond.channels.zeromq

import java.nio.channels.{ClosedByInterruptException, Selector}
import java.nio.charset.StandardCharsets.UTF_8

import almond.channels._
import almond.logger.LoggerContext
import cats.effect.IO
import cats.syntax.apply._
import org.zeromq.{SocketType, ZMQ, ZMQException}
import org.zeromq.ZMQ.{PollItem, Poller}
import zmq.ZError

import scala.concurrent.duration.Duration
import cats.Parallel

final class ZeromqConnection(
  params: ConnectionParameters,
  bind: Boolean,
  identityOpt: Option[String],
  threads: ZeromqThreads,
  lingerPeriod: Option[Duration],
  logCtx: LoggerContext
) extends Connection {

  import ZeromqConnection._

  private val log = logCtx(getClass)

  private def routerDealer =
    if (bind) SocketType.ROUTER
    else SocketType.DEALER
  private def inverseRouterDealer =
    if (bind) SocketType.DEALER
    else SocketType.ROUTER
  private def pubSub =
    if (bind) SocketType.PUB
    else SocketType.SUB
  private def repReq =
    if (bind) SocketType.REP
    else SocketType.REQ

  private val requests0 = ZeromqSocket(
    threads.ecs(Channel.Requests),
    routerDealer,
    bind,
    params.uri(Channel.Requests),
    identityOpt.map(_.getBytes(UTF_8)),
    None,
    threads.context,
    params.key,
    params.signature_scheme.getOrElse(defaultSignatureScheme),
    lingerPeriod,
    logCtx
  )

  private val control0 = ZeromqSocket(
    threads.ecs(Channel.Control),
    routerDealer,
    bind,
    params.uri(Channel.Control),
    identityOpt.map(_.getBytes(UTF_8)),
    None,
    threads.context,
    params.key,
    params.signature_scheme.getOrElse(defaultSignatureScheme),
    lingerPeriod,
    logCtx
  )

  private val publish0 = ZeromqSocket(
    threads.ecs(Channel.Publish),
    pubSub,
    bind,
    params.uri(Channel.Publish),
    None,
    Some(Array.emptyByteArray),
    threads.context,
    params.key,
    params.signature_scheme.getOrElse(defaultSignatureScheme),
    lingerPeriod,
    logCtx
  )

  private val stdin0 = ZeromqSocket(
    threads.ecs(Channel.Input),
    inverseRouterDealer,
    bind,
    params.uri(Channel.Input),
    identityOpt.map(_.getBytes(UTF_8)),
    None,
    threads.context,
    params.key,
    params.signature_scheme.getOrElse(defaultSignatureScheme),
    lingerPeriod,
    logCtx
  )

  private val heartBeatThreadOpt: Option[Thread] =
    if (bind)
      Some(
        new Thread(s"ZeroMQ-HeartBeat") {
          setDaemon(true)
          override def run(): Unit = {

            val ignoreExceptions: PartialFunction[Throwable, Unit] = {
              case ex: ZMQException if ex.getErrorCode == 4                                       =>
              case ex: ZError.IOException if ex.getCause.isInstanceOf[ClosedByInterruptException] =>
              case _: ClosedByInterruptException                                                  =>
            }

            val heartbeat = threads.context.socket(repReq)

            heartbeat.setLinger(1000)
            heartbeat.bind(params.heartbeatUri)

            try
              while (true) {
                val msg = heartbeat.recv()
                heartbeat.send(msg) // FIXME Ignoring return value, that indicates success or not
              }
            catch ignoreExceptions
            finally
              try heartbeat.close()
              catch ignoreExceptions
          }
        }
      )
    else
      None

  private def channelSocket0(channel: Channel): ZeromqSocket =
    channel match {
      case Channel.Requests => requests0
      case Channel.Control  => control0
      case Channel.Publish  => publish0
      case Channel.Input    => stdin0
    }

  @volatile private var selectorOpt = Option.empty[Selector]

  private def withSelector[T](f: Selector => T): T =
    selectorOpt match {
      case Some(selector) =>
        f(selector)
      case None =>
        throw new Exception("Channel not opened")
    }

  val open: IO[Unit] = {

    val log0 = IO(log.debug(s"Opening channels for $params"))

    val channels = Seq(
      requests0,
      control0,
      publish0,
      stdin0
    )

    val t = channels.foldLeft(IO.unit)((acc, c) => acc *> c.open)

    val other = IO {
      synchronized {
        for (t <- heartBeatThreadOpt if t.getState == Thread.State.NEW)
          t.start()
        if (selectorOpt.isEmpty)
          selectorOpt = Some(Selector.open())
      }
    }.evalOn(threads.selectorOpenCloseEc)

    log0 *> t *> other
  }

  def send(channel: Channel, message: Message): IO[Unit] = {

    val log0 = IO(log.debug(s"Sending message on $params from $channel"))

    log0 *> channelSocket0(channel).send(message)
  }

  def tryRead(channels: Seq[Channel], pollingDelay: Duration): IO[Option[(Channel, Message)]] =
    IO {

      // log.debug(s"Trying to read on $params from $channels") // un-comment if you're, like, really debugging hard

      val pollItems = channels
        .map { channel =>
          val socket = channelSocket0(channel)
          (channel, new PollItem(socket.channel, Poller.POLLIN))
        }

      withSelector { selector =>
        ZMQ.poll(selector, pollItems.map(_._2).toArray, pollingDelay.toMillis)
      }

      pollItems
        .collectFirst {
          case (channel, pi) if pi.isReadable =>
            channelSocket0(channel)
              .read
              .map(_.map((channel, _)))
        }
        .getOrElse(IO.pure(None))
    }.evalOn(threads.pollingEc).flatMap(identity)

  def close(partial: Boolean, lingerDuration: Duration): IO[Unit] = {

    val log0 = IO(log.debug(s"Closing channels for $params"))

    val channels = List(
      requests0,
      control0,
      stdin0
    ) ::: (if (partial) Nil else List(publish0))

    val t = Parallel.parTraverse(channels)(_.close(lingerDuration))

    val other = IO {
      log.debug(s"Closing things for $params" + (if (partial) " (partial)" else ""))

      if (!partial)
        heartBeatThreadOpt.foreach(_.interrupt())

      selectorOpt.foreach(_.close())
      selectorOpt = None

      log.debug(s"Closed channels for $params" + (if (partial) " (partial)" else ""))
    }.evalOn(threads.selectorOpenCloseEc)

    log0 *> t *> other
  }

}

object ZeromqConnection {

  private def defaultSignatureScheme = "hmacsha256"

  def apply(
    connection: ConnectionParameters,
    bind: Boolean,
    identityOpt: Option[String],
    threads: ZeromqThreads,
    lingerPeriod: Option[Duration],
    logCtx: LoggerContext
  ): IO[ZeromqConnection] =
    IO(
      new ZeromqConnection(
        connection,
        bind,
        identityOpt,
        threads,
        lingerPeriod,
        logCtx
      )
    ).evalOn(threads.selectorOpenCloseEc)

}
