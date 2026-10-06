package almond.channels.zeromq

import java.net.URI
import java.nio.channels.{
  CancelledKeyException,
  ClosedByInterruptException,
  ClosedChannelException,
  ClosedSelectorException,
  SelectionKey,
  Selector
}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import almond.channels._
import almond.logger.LoggerContext
import almond.protocol.{Header, IopubWelcome, Protocol}
import cats.Parallel
import cats.effect.IO
import cats.syntax.apply._
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import org.zeromq.{SocketType, ZMQException}
import zmq.ZError

import scala.concurrent.Promise
import scala.concurrent.duration.{Deadline, Duration, FiniteDuration}
import scala.jdk.CollectionConverters._

final class ZeromqConnection(
  params: ConnectionParameters,
  bind: Boolean,
  identityOpt: Option[String],
  threads: ZeromqThreads,
  lingerPeriod: Option[Duration],
  logCtx: LoggerContext,
  bindToRandomPorts: Boolean
) extends Connection {

  import ZeromqConnection._

  @deprecated("Use the override accepting bindToRandomPorts", "0.14.2")
  def this(
    params: ConnectionParameters,
    bind: Boolean,
    identityOpt: Option[String],
    threads: ZeromqThreads,
    lingerPeriod: Option[Duration],
    logCtx: LoggerContext
  ) = this(
    params,
    bind,
    identityOpt,
    threads,
    lingerPeriod,
    logCtx,
    bindToRandomPorts = true
  )

  private val log = logCtx(getClass)

  private def routerDealer =
    if (bind) SocketType.ROUTER
    else SocketType.DEALER
  // XPUB rather than PUB since protocol 5.5, so that we get notified of subscriptions
  // and can send iopub_welcome messages
  private def pubSub =
    if (bind) SocketType.XPUB
    else SocketType.SUB
  private def repReq =
    if (bind) SocketType.REP
    else SocketType.REQ

  private val requests0 = ZeromqSocket(
    threads.channelEces(Channel.Requests),
    routerDealer,
    bind,
    params.uri(Channel.Requests),
    identityOpt.map(_.getBytes(UTF_8)),
    None,
    threads.context,
    params.key,
    params.signature_scheme.getOrElse(defaultSignatureScheme),
    lingerPeriod,
    logCtx,
    bindToRandomPort = bindToRandomPorts
  )

  private val control0 = ZeromqSocket(
    threads.channelEces(Channel.Control),
    routerDealer,
    bind,
    params.uri(Channel.Control),
    identityOpt.map(_.getBytes(UTF_8)),
    None,
    threads.context,
    params.key,
    params.signature_scheme.getOrElse(defaultSignatureScheme),
    lingerPeriod,
    logCtx,
    bindToRandomPort = bindToRandomPorts
  )

  private val publish0 = ZeromqSocket(
    threads.channelEces(Channel.Publish),
    pubSub,
    bind,
    params.uri(Channel.Publish),
    None,
    Some(Array.emptyByteArray),
    threads.context,
    params.key,
    params.signature_scheme.getOrElse(defaultSignatureScheme),
    lingerPeriod,
    logCtx,
    bindToRandomPort = bindToRandomPorts
  )

  private val stdin0 = ZeromqSocket(
    threads.channelEces(Channel.Input),
    routerDealer,
    bind,
    params.uri(Channel.Input),
    identityOpt.map(_.getBytes(UTF_8)),
    None,
    threads.context,
    params.key,
    params.signature_scheme.getOrElse(defaultSignatureScheme),
    lingerPeriod,
    logCtx,
    bindToRandomPort = bindToRandomPorts
  )

  private val heartBeatPortPromiseAndUri = {
    lazy val parsedUri = new URI(params.heartbeatUri)
    if (bind && bindToRandomPorts && parsedUri.getPort <= 0)
      Some((Promise[Int](), ZeromqSocketImpl.removePort(parsedUri).toASCIIString))
    else
      None
  }
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
            heartBeatPortPromiseAndUri match {
              case Some((promise, uri0)) =>
                val port = heartbeat.bindToRandomPort(uri0)
                promise.success(port)
              case None =>
                heartbeat.bind(params.heartbeatUri)
            }

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

  // session of the iopub_welcome messages we send
  private lazy val welcomeSession = UUID.randomUUID().toString

  private def welcomeMessage(subscription: Seq[Byte]): Option[Message] = {
    val decoder = UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val subscriptionStrOpt =
      try Some(decoder.decode(ByteBuffer.wrap(subscription.toArray)).toString)
      catch {
        case _: CharacterCodingException =>
          None
      }
    subscriptionStrOpt.map { subscriptionStr =>
      val msgType = IopubWelcome.messageType.messageType
      val header = Header(
        msg_id = UUID.randomUUID().toString,
        username = "kernel",
        session = welcomeSession,
        msg_type = msgType,
        version = Some(Protocol.versionStr)
      )
      Message(
        // the topic must match the subscription for the subscriber to get the message
        idents = Seq(if (subscription.isEmpty) msgType.getBytes(UTF_8).toSeq else subscription),
        header = writeToArray(header),
        // no parent header for iopub_welcome
        parentHeader = "{}".getBytes(UTF_8),
        metadata = "{}".getBytes(UTF_8),
        content = writeToArray(IopubWelcome(subscriptionStr))
      )
    }
  }

  private val handleSubscriptionEvent: IO[Unit] =
    publish0.readSubscriptionEvent.flatMap {
      case Some(event) if event.subscribe =>
        welcomeMessage(event.topic) match {
          case Some(msg) =>
            IO(log.debug(s"Sending iopub_welcome on $actualParams")) *> publish0.send(msg)
          case None =>
            IO(log.warn("Ignoring subscription with non-UTF-8 topic"))
        }
      case _ =>
        IO.unit
    }

  @volatile private var selectorOpt = Option.empty[Selector]
  private var actualParams          = params

  val open: IO[Map[Option[Channel], Int]] = {

    val log0 = IO(log.debug(s"Opening channels for $params"))

    val channels = Seq[(Channel, ZeromqSocket)](
      Channel.Requests -> requests0,
      Channel.Control  -> control0,
      Channel.Publish  -> publish0,
      Channel.Input    -> stdin0
    )

    val t = channels.foldLeft(IO.pure(Map.empty[Option[Channel], Int])) {
      case (acc, (channel, socket)) =>
        for {
          map     <- acc
          portOpt <- socket.open
        } yield map ++ portOpt.map((Some(channel), _)).toSeq
    }

    val other = IO {
      synchronized {
        for (thread <- heartBeatThreadOpt if thread.getState == Thread.State.NEW)
          thread.start()
        if (selectorOpt.isEmpty)
          selectorOpt = Some(Selector.open())
      }
    }.evalOn(threads.selectorOpenCloseEces)

    val maybeHeartBeatPort = heartBeatPortPromiseAndUri match {
      case Some((promise, _)) =>
        IO.fromFuture(IO.pure(promise.future)).map(port => Seq(None -> port))
      case None =>
        IO.pure(Nil)
    }

    for {
      _          <- log0
      ports      <- t
      _          <- other
      extraPorts <- maybeHeartBeatPort
    } yield {
      val ports0 = ports ++ extraPorts
      actualParams = actualParams.copy(
        stdin_port = ports0.getOrElse(Some(Channel.Input), actualParams.stdin_port),
        control_port = ports0.getOrElse(Some(Channel.Control), actualParams.control_port),
        hb_port = ports0.getOrElse(None, actualParams.hb_port),
        shell_port = ports0.getOrElse(Some(Channel.Requests), actualParams.shell_port),
        iopub_port = ports0.getOrElse(Some(Channel.Publish), actualParams.iopub_port)
      )
      ports0
    }
  }

  def send(channel: Channel, message: Message): IO[Unit] = {

    val log0 = IO(log.debug(s"Sending message on $actualParams from $channel"))

    // Sending processes the commands pending for the socket, possibly consuming the one that
    // made its file descriptor readable, and that tryRead might be waiting for. So we wake
    // tryRead up if input is pending after sending.
    val sendAndMaybeWakeUpPolling = channelSocket0(channel).sendAndCheckInput(message).flatMap {
      pendingInput =>
        IO {
          if (pendingInput)
            selectorOpt.foreach(_.wakeup())
        }
    }

    log0 *> sendAndMaybeWakeUpPolling
  }

  /** Waits for the file descriptors of the passed sockets to be readable, for at most timeout */
  private def waitForCommands(
    selector: Selector,
    sockets: Seq[ZeromqSocket],
    timeout: Duration
  ): Unit = {
    val fds = sockets.map(_.fd).toSet
    // don't get woken up by the sockets we're not polling this time
    for (key <- selector.keys().asScala if key.isValid && !fds(key.channel()))
      key.interestOps(0)
    for (fd <- fds)
      Option(fd.keyFor(selector)) match {
        case Some(key) =>
          if (key.interestOps() != SelectionKey.OP_READ)
            key.interestOps(SelectionKey.OP_READ)
        case None =>
          fd.register(selector, SelectionKey.OP_READ)
      }
    timeout match {
      case d: FiniteDuration if d <= Duration.Zero => selector.selectNow()
      case d: FiniteDuration                       => selector.select(math.max(1L, d.toMillis))
      case _                                       => selector.select()
    }
    selector.selectedKeys().clear()
  }

  def tryRead(
    channels: Seq[Channel],
    pollingDelay: Duration
  ): IO[Option[Either[Unit, (Channel, Message)]]] = {

    // log.debug(s"Trying to read on $actualParams from $channels") // un-comment if you're, like, really debugging hard

    // We don't use ZMQ.poll here, as it makes the sockets process their pending commands, while
    // JeroMQ sockets aren't thread-safe, and other threads use them to send messages (see
    // https://github.com/almond-sh/almond/issues/549). Instead, we wait for the socket file
    // descriptors to be readable on the polling thread, and check for input on the thread of
    // each socket.

    val sockets = channels.toList.map(channel => (channel, channelSocket0(channel)))

    // checking all sockets, so that they all process their pending commands, and their file
    // descriptors don't stay readable
    val readableChannel: IO[Option[Channel]] =
      Parallel.parTraverse(sockets) {
        case (channel, socket) =>
          socket.hasPendingInput.map((channel, _))
      }.map(_.collectFirst { case (channel, true) => channel })

    // file descriptors can also become readable because of commands not related to input
    // (new peers, …), so we keep waiting until the deadline if no input is pending
    def waitForInput(selector: Selector, deadlineOpt: Option[Deadline]): IO[Option[Channel]] =
      readableChannel.flatMap {
        case None =>
          val timeLeft = deadlineOpt.fold[Duration](Duration.Inf)(_.timeLeft)
          if (timeLeft <= Duration.Zero) IO.pure(None)
          else
            IO(waitForCommands(selector, sockets.map(_._2), timeLeft))
              .evalOn(threads.pollingEces) *>
              waitForInput(selector, deadlineOpt)
        case readableOpt =>
          IO.pure(readableOpt)
      }

    val closed: PartialFunction[Throwable, Either[Unit, Option[Channel]]] = {
      case _: ClosedSelectorException                                               => Left(())
      case _: ClosedChannelException                                                => Left(())
      case e: ZError.IOException if e.getCause.isInstanceOf[ClosedChannelException] => Left(())
      // keys get cancelled when sockets get closed
      case _: CancelledKeyException => Left(())
    }

    IO(selectorOpt).flatMap {
      case None =>
        IO(log.debug("Connection not opened")).as(Some(Left(())))
      case Some(selector) =>
        val deadlineOpt = pollingDelay match {
          case d: FiniteDuration => Some(d.fromNow)
          case _                 => None
        }
        waitForInput(selector, deadlineOpt)
          .map[Either[Unit, Option[Channel]]](Right(_))
          .recover(closed)
          .flatMap {
            case Left(()) =>
              IO.pure(Some(Left(())))
            case Right(None) =>
              IO.pure(None)
            case Right(Some(Channel.Publish)) if bind =>
              // the only things we can read on our XPUB socket are subscription events
              handleSubscriptionEvent.as(Option.empty[Either[Unit, (Channel, Message)]])
            case Right(Some(channel)) =>
              channelSocket0(channel)
                .read
                .map(_.map(msg => Right((channel, msg))))
          }
    }
  }

  def close(partial: Boolean, lingerDuration: Duration): IO[Unit] = {

    val log0 = IO(log.debug(s"Closing channels for $actualParams"))

    val channels = List(
      requests0,
      control0,
      stdin0
    ) ::: (if (partial) Nil else List(publish0))

    val t = Parallel.parTraverse(channels)(_.close(lingerDuration))

    val other = IO {
      log.debug(s"Closing things for $actualParams" + (if (partial) " (partial)" else ""))

      if (!partial)
        heartBeatThreadOpt.foreach(_.interrupt())

      selectorOpt.foreach(_.close())
      selectorOpt = None

      log.debug(s"Closed channels for $actualParams" + (if (partial) " (partial)" else ""))
    }.evalOn(threads.selectorOpenCloseEces)

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
    logCtx: LoggerContext,
    bindToRandomPorts: Boolean
  ): IO[ZeromqConnection] =
    IO(
      new ZeromqConnection(
        connection,
        bind,
        identityOpt,
        threads,
        lingerPeriod,
        logCtx,
        bindToRandomPorts = bindToRandomPorts
      )
    ).evalOn(threads.selectorOpenCloseEces)

  @deprecated("Use override accepting bindToRandomPorts", "0.14.2")
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
        logCtx,
        bindToRandomPorts = true
      )
    ).evalOn(threads.selectorOpenCloseEces)

}
