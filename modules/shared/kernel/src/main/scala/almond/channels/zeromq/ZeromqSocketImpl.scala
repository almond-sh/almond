package almond.channels.zeromq

import java.nio.charset.StandardCharsets.UTF_8

import almond.channels.Message
import almond.logger.LoggerContext
import almond.util.Secret
import cats.effect.IO
import cats.syntax.apply._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.zeromq.{SocketType, ZMQ}

import java.net.URI
import java.nio.channels.{ClosedChannelException, SelectableChannel}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

final class ZeromqSocketImpl(
  ec: ExecutionContext,
  socketType: SocketType,
  bind: Boolean,
  uri: String,
  identityOpt: Option[Array[Byte]],
  subscribeOpt: Option[Array[Byte]],
  context: ZMQ.Context,
  key: Secret[String],
  algorithm: String,
  lingerPeriod: Option[Duration],
  logCtx: LoggerContext,
  bindToRandomPort: Boolean
) extends ZeromqSocket {

  @deprecated("Use the override accepting bindToRandomPort", "0.14.2")
  def this(
    ec: ExecutionContext,
    socketType: SocketType,
    bind: Boolean,
    uri: String,
    identityOpt: Option[Array[Byte]],
    subscribeOpt: Option[Array[Byte]],
    context: ZMQ.Context,
    key: Secret[String],
    algorithm: String,
    lingerPeriod: Option[Duration],
    logCtx: LoggerContext
  ) = this(
    ec = ec,
    socketType = socketType,
    bind = bind,
    uri = uri,
    identityOpt = identityOpt,
    subscribeOpt = subscribeOpt,
    context = context,
    key = key,
    algorithm = algorithm,
    lingerPeriod = lingerPeriod,
    logCtx = logCtx,
    bindToRandomPort = true
  )

  import ZeromqSocketImpl._

  private val log = logCtx(getClass)

  private val algorithm0  = algorithm.filter(_ != '-')
  private val macInstance = Mac.getInstance(algorithm0)
  private val enableMac   = key.value.nonEmpty
  if (enableMac)
    macInstance.init(new SecretKeySpec(key.value.getBytes(UTF_8), algorithm0))

  private def hmac(args: Array[Byte]*): String =
    if (enableMac) {
      for (b <- args)
        macInstance.update(b)

      macInstance
        .doFinal()
        .map(s => f"$s%02x")
        .mkString
    }
    else
      ""

  val channel = context.socket(socketType)
  for (b <- identityOpt)
    channel.setIdentity(b)
  lingerPeriod.foreach {
    case f: FiniteDuration =>
      log.debug(s"Setting linger period of $channel to $f")
      channel.setLinger(f.toMillis.toInt)
    case _ =>
      log.debug(s"Setting linger period of $channel to infinite")
      channel.setLinger(-1)
  }
  if (socketType == SocketType.ROUTER)
    channel.setRouterHandover(true)
  if (socketType == SocketType.PUB || socketType == SocketType.XPUB)
    // If publisher's socket queue gets filled, all new messages are dropped; remove queue size constraint
    channel.setHWM(0)
  if (socketType == SocketType.XPUB)
    // Pass all subscriptions to us, not just the first one for a given topic, so that we can send
    // an iopub_welcome message each time a client subscribes
    channel.setXpubVerbose(true)

  // JeroMQ sockets aren't thread-safe, so channel is only used from ec (that should be
  // single-threaded) from here on. Having the publish socket accessed concurrently by
  // several threads could corrupt its internal state, see
  // https://github.com/almond-sh/almond/issues/549.

  // Only reads a field of the socket, that doesn't change after its creation
  val fd: SelectableChannel = channel.getFD

  @volatile private var opened = false
  @volatile private var closed = false

  private var pickedRandomPort = Option.empty[Int]

  val open: IO[Option[Int]] = {

    def connectOrBind = IO {
      if (bind) {
        lazy val parsedUri = new URI(uri)
        if (bindToRandomPort && parsedUri.getPort <= 0) {
          val uri0 = ZeromqSocketImpl.removePort(parsedUri).toASCIIString
          val port = channel.bindToRandomPort(uri0)
          (true, Some(port))
        }
        else
          (channel.bind(uri), None)
      }
      else
        (channel.connect(uri), None)
    }.flatMap {
      case (true, portOpt) =>
        IO {
          log.debug {
            if (bind)
              s"Listening on $uri"
            else
              s"Connected to $uri"
          }
          opened = true
          pickedRandomPort = portOpt
          portOpt
        }
      case (false, _) =>
        IO.raiseError(new Exception(s"Cannot bind / connect channel $uri"))
    }

    def maybeSubscribe = subscribeOpt.filter(_ => !bind) match {
      case Some(b) =>
        def asStr = Try(new String(b, "UTF-8")).getOrElse(b.toString)
        IO {
          channel.subscribe(b)
        }.flatMap {
          case true =>
            IO {
              log.debug(s"Subscribed to $asStr on $uri")
            }
          case false =>
            IO.raiseError(
              new Exception(s"Cannot subscribe to $asStr on channel $uri")
            )
        }
      case None =>
        IO.unit
    }

    val t = IO {
      if (opened)
        IO.pure(pickedRandomPort)
      else
        for {
          portOpt <- connectOrBind
          _       <- maybeSubscribe
        } yield portOpt
    }

    delayedCondition(!closed, "Channel is closed")(
      t.evalOn(ec).flatMap(t0 => t0)
    )
  }

  private def identsAsStrings(idents: Seq[Seq[Byte]]) =
    idents.map { b =>
      Try(new String(b.toArray, UTF_8))
        .toOption
        .getOrElse("???")
    }

  def send(message: Message): IO[Unit] =
    send0(message, checkInput = false).void

  def sendAndCheckInput(message: Message): IO[Boolean] =
    send0(message, checkInput = true)

  private def send0(message: Message, checkInput: Boolean): IO[Boolean] =
    delayedCondition(!closed && opened, "Channel is not opened in send")(
      IO {

        ensureOpened()

        log.debug {
          val nl = System.lineSeparator()
          "Sending:" + nl +
            "  header: " +
            Try(new String(message.header, "UTF-8"))
              .toOption
              .getOrElse(message.header.toString) +
            nl +
            "  content: " +
            Try(new String(message.content, "UTF-8"))
              .toOption
              .getOrElse(message.content.toString) +
            nl +
            "  idents: " + identsAsStrings(message.idents)
        }

        for (c <- message.idents)
          channel.send(c.toArray, ZMQ.SNDMORE)

        channel.send(delimiterBytes, ZMQ.SNDMORE)
        channel.send(
          hmac(message.header, message.parentHeader, message.metadata, message.content),
          ZMQ.SNDMORE
        )
        channel.send(message.header, ZMQ.SNDMORE)
        channel.send(message.parentHeader, ZMQ.SNDMORE)
        channel.send(message.metadata, ZMQ.SNDMORE)
        channel.send(message.content, if (message.buffers.isEmpty) 0 else ZMQ.SNDMORE)
        val lastBufferIdx = message.buffers.length - 1
        for ((buf, idx) <- message.buffers.iterator.zipWithIndex)
          channel.send(buf, if (idx == lastBufferIdx) 0 else ZMQ.SNDMORE)

        checkInput && hasPendingInput0()
      }.evalOn(ec)
    )

  val read: IO[Option[Message]] = delayedCondition(
    !closed && opened,
    "Channel is not opened in read"
  )(
    IO {

      val idents =
        Iterator.continually(channel.recv())
          .takeWhile(!_.sameElements(delimiterBytes))
          .toVector
          .map(_.toSeq)

      val signature = channel.recvStr()

      // FIXME Check for null return values of recv
      val header       = channel.recv()
      val parentHeader = channel.recv()
      val metaData     = channel.recv()
      val content      = channel.recv()

      // Extra frames after content are binary buffers (not covered by the signature).
      // They must be read here, or they'd be picked as the idents of the next message.
      val buffers =
        Iterator.continually(channel)
          .takeWhile(_.hasReceiveMore)
          .map(_.recv())
          .toVector

      val message = Message(idents, header, parentHeader, metaData, content, buffers)

      val expectedSignature = hmac(header, parentHeader, metaData, content)

      if (expectedSignature == signature || !enableMac) {
        log.debug {
          val nl = System.lineSeparator()
          val headerStr = Try(new String(message.header, UTF_8))
            .getOrElse(message.header.toString)
          s"Received on $uri:" + nl +
            "  header: " +
            headerStr +
            nl +
            "  content: " +
            Try(new String(message.content, "UTF-8"))
              .toOption
              .getOrElse(message.content.toString) +
            nl +
            "  idents: " + identsAsStrings(message.idents) +
            (if (message.buffers.isEmpty) ""
             else nl + "  buffers: " + message.buffers.map(_.length).mkString(", ") + " bytes")
        }
        Some(message)
      }
      else {
        log.error(s"Invalid HMAC signature, got '$signature', expected '$expectedSignature'")
        None
      }
    }.evalOn(ec)
  )

  val readSubscriptionEvent: IO[Option[ZeromqSocket.SubscriptionEvent]] = delayedCondition(
    !closed && opened,
    "Channel is not opened in readSubscriptionEvent"
  )(
    IO {
      // subscription events are single frames: 1 (subscribe) or 0 (unsubscribe), then the topic
      val frames =
        Iterator(channel.recv()) ++
          Iterator.continually(channel)
            .takeWhile(_.hasReceiveMore)
            .map(_.recv())
      frames.toVector match {
        case Vector(frame) if frame != null && frame.nonEmpty && (frame(0) == 0 || frame(0) == 1) =>
          val event = ZeromqSocket.SubscriptionEvent(
            subscribe = frame(0) == 1,
            topic = frame.toSeq.drop(1)
          )
          log.debug {
            val topic = Try(new String(event.topic.toArray, UTF_8)).getOrElse(event.topic.toString)
            "Received " +
              (if (event.subscribe) "subscription" else "unsubscription") +
              s" for '$topic' on $uri"
          }
          Some(event)
        case other =>
          log.warn(s"Ignoring unrecognized subscription event (${other.length} frame(s)) on $uri")
          None
      }
    }.evalOn(ec)
  )

  private def hasPendingInput0(): Boolean = {
    if (closed)
      throw new ClosedChannelException
    // getEvents returns -1 if the context is being terminated
    val events = channel.getEvents
    events >= 0 && (events & ZMQ.Poller.POLLIN) != 0
  }

  val hasPendingInput: IO[Boolean] =
    IO(hasPendingInput0()).evalOn(ec)

  def close(lingerDuration: Duration): IO[Unit] = {

    val t = IO {
      if (!closed) {
        val linger = lingerDuration match {
          case d: FiniteDuration => d.toMillis.toInt
          case _                 => -1
        }
        if (channel.getLinger != linger)
          channel.setLinger(linger)
        channel.close()
        closed = true
      }
    }

    delayedCondition(opened, "Channel is not opened in close")(t.evalOn(ec))
  }

  private def ensureOnlyOpened(): Unit = {
    if (!opened)
      throw new java.io.IOException("Channel is not opened")
  }

  private def ensureNotClosed(): Unit = {
    if (closed)
      throw new java.io.IOException("Channel is closed")
  }

  private def ensureOpened(): Unit = {
    ensureNotClosed()
    ensureOnlyOpened()
  }

}

object ZeromqSocketImpl {

  private val delimiterBytes: Array[Byte] =
    "<IDS|MSG>".getBytes(UTF_8)

  private def delayedCondition[T](cond: => Boolean, msg: String)(t: IO[T]): IO[T] =
    IO(assert(cond, msg)) *> t

  private[zeromq] def removePort(uri: URI): URI =
    new URI(
      uri.getScheme,
      uri.getUserInfo,
      uri.getHost,
      -1, // clear port
      uri.getPath,
      uri.getQuery,
      uri.getFragment
    )
}
