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
  logCtx: LoggerContext
) extends ZeromqSocket {

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
  if (socketType == SocketType.PUB)
    // If publisher's socket queue gets filled, all new messages are dropped; remove queue size constraint
    channel.setHWM(0)

  @volatile private var opened = false
  @volatile private var closed = false

  val open: IO[Unit] = {

    def connectOrBind = IO {
      if (bind)
        channel.bind(uri)
      else
        channel.connect(uri)
    }.flatMap {
      case true =>
        IO {
          log.debug {
            if (bind)
              s"Listening on $uri"
            else
              s"Connected to $uri"
          }
          opened = true
        }
      case false =>
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
        IO.unit
      else
        connectOrBind.flatMap(_ => maybeSubscribe)
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
    delayedCondition(!closed && opened, "Channel is not opened in send")(
      IO {

        ensureOpened()

        log.debug(
          "Sending:\n" +
            "  header: " +
            Try(new String(message.header, "UTF-8"))
              .toOption
              .getOrElse(message.header.toString) +
            "\n" +
            "  content: " +
            Try(new String(message.content, "UTF-8"))
              .toOption
              .getOrElse(message.content.toString) +
            "\n" +
            "  idents: " + identsAsStrings(message.idents)
        )

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
        channel.send(message.content)

        ()
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

      val message = Message(idents, header, parentHeader, metaData, content)

      val expectedSignature = hmac(header, parentHeader, metaData, content)

      if (expectedSignature == signature || !enableMac) {
        log.debug {
          val headerStr = Try(new String(message.header, UTF_8))
            .getOrElse(message.header.toString)
          s"Received on $uri:\n" +
            "  header: " +
            headerStr +
            "\n" +
            "  content: " +
            Try(new String(message.content, "UTF-8"))
              .toOption
              .getOrElse(message.content.toString) +
            "\n" +
            "  idents: " + identsAsStrings(message.idents)
        }
        Some(message)
      }
      else {
        log.error(s"Invalid HMAC signature, got '$signature', expected '$expectedSignature'")
        None
      }
    }.evalOn(ec)
  )

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

}
