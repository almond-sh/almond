package almond.channels.zeromq

import java.nio.charset.StandardCharsets.UTF_8

import almond.channels.Message
import almond.logger.LoggerContext
import almond.util.Secret
import cats.effect.IO
import cats.syntax.apply._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.zeromq.ZMQ

import scala.concurrent.ExecutionContext
import scala.util.Try

final class ZeromqSocketImpl(
  ec: ExecutionContext,
  socketType: Int,
  bind: Boolean,
  uri: String,
  identityOpt: Option[Array[Byte]],
  subscribeOpt: Option[Array[Byte]],
  context: ZMQ.Context,
  key: Secret[String],
  algorithm: String,
  logCtx: LoggerContext
) extends ZeromqSocket {

  import ZeromqSocketImpl._

  private val log = logCtx(getClass)

  private val algorithm0 = algorithm.filter(_ != '-')
  private val macInstance = Mac.getInstance(algorithm0)
  private val enableMac = key.value.nonEmpty
  if (enableMac) {
    macInstance.init(new SecretKeySpec(key.value.getBytes(UTF_8), algorithm0))
  }

  private def hmac(args: String*): String = {
    if (enableMac) {
      for (s <- args)
        macInstance.update(s.getBytes(UTF_8))

      macInstance
        .doFinal()
        .map(s => f"$s%02x")
        .mkString
    } else {
      ""
    }
  }


  val channel = context.socket(socketType)
  for (b <- identityOpt)
    channel.setIdentity(b)
  channel.setLinger(1000)

  @volatile private var opened = false
  @volatile private var closed = false

  val open: IO[Unit] = {

    val t = IO {
      if (opened)
        IO.unit
      else {
        val res =
          if (bind)
            channel.bind(uri)
          else
            channel.connect(uri)

        if (res) {
          for (b <- subscribeOpt if !bind)
            channel.subscribe(b)

          opened = true
          IO.unit
        } else
          IO.raiseError(new Exception(s"Cannot bind / connect channel $uri"))
      }
    }

    delayedCondition(!closed, "Channel is closed")(
      IO.shift(ec)
        .flatMap(_ => t)
        .flatMap(t0 => t0)
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
      IO.shift(ec) *> IO {

        ensureOpened()

        log.debug(s"Sending message with header ${message.header} and idents ${identsAsStrings(message.idents)})")

        for (c <- message.idents)
          channel.send(c.toArray, ZMQ.SNDMORE)

        channel.send(delimiterBytes, ZMQ.SNDMORE)
        channel.send(hmac(message.header, message.parentHeader, message.metadata, message.content), ZMQ.SNDMORE)
        channel.send(message.header, ZMQ.SNDMORE)
        channel.send(message.parentHeader, ZMQ.SNDMORE)
        channel.send(message.metadata, ZMQ.SNDMORE)
        channel.send(message.content)

        ()
      }
    )

  val read: IO[Option[Message]] = delayedCondition(!closed && opened, "Channel is not opened in read")(
    IO.shift(ec) *> IO {

      val idents =
        Stream.continually(channel.recv())
          .takeWhile(!_.sameElements(delimiterBytes))
          .toVector
          .map(_.toSeq)

      val signature = channel.recvStr()

      val header = channel.recvStr()
      val parentHeader = channel.recvStr()
      val metaData = channel.recvStr()
      val content = channel.recvStr()

      val message = Message(idents, header, parentHeader, metaData, content)

      val expectedSignature = hmac(header, parentHeader, metaData, content)

      if (expectedSignature == signature || !enableMac) {
        log.debug(s"Received on $channel message with header ${message.header} and idents ${identsAsStrings(message.idents)})")
        Some(message)
      } else {
        log.error(s"Invalid HMAC signature, got '$signature', expected '$expectedSignature'")
        None
      }
    }
  )

  val close: IO[Unit] = {

    val t = IO {
      if (!closed) {
        channel.close()
        closed = true
      }
    }

    delayedCondition(opened, "Channel is not opened in close")(IO.shift(ec) *> t)
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
