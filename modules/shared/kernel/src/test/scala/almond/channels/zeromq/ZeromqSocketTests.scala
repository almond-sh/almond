package almond.channels.zeromq

import java.util.concurrent.Executors

import almond.channels.{ConnectionParameters, Message}
import almond.logger.LoggerContext
import almond.util.Secret
import cats.effect.unsafe.IORuntime
import org.zeromq.{SocketType, ZMQ}
import utest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import java.nio.charset.StandardCharsets

object ZeromqSocketTests extends TestSuite {

  private val ctx       = ZMQ.context(4)
  private val ioRuntime = IORuntime.global

  override def utestAfterAll() =
    ctx.term()

  private def randomPort(): Int = {
    val s    = new java.net.ServerSocket(0)
    val port = s.getLocalPort
    s.close()
    port
  }

  val tests = Tests {

    test("simple") {

      val repEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val reqEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val port = randomPort()

      val key = Secret.randomUuid()

      val logCtx = LoggerContext.nop

      val rep = ZeromqSocket(
        repEc,
        SocketType.REP,
        bind = true,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = false
      )

      val req = ZeromqSocket(
        reqEc,
        SocketType.REQ,
        bind = false,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = false
      )

      val msg = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        "content".getBytes(StandardCharsets.UTF_8)
      )

      val t =
        for {
          _       <- rep.open
          _       <- req.open
          _       <- req.send(msg)
          readOpt <- rep.read
          _ = assert(readOpt.contains(msg))
          // FIXME Closing should be enforced via bracketing
          _ <- req.close(lingerDuration = 5.seconds)
          _ <- rep.close(lingerDuration = 5.seconds)
        } yield ()

      t.unsafeRunSync()(ioRuntime)
    }

    test("simpleWithNoKey") {

      val repEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val reqEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val port = randomPort()

      val key = Secret("") // having no key disables signature checking

      val logCtx = LoggerContext.nop

      val rep = ZeromqSocket(
        repEc,
        SocketType.REP,
        bind = true,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = false
      )

      val req = ZeromqSocket(
        reqEc,
        SocketType.REQ,
        bind = false,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = false
      )

      val msg = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        "content".getBytes(StandardCharsets.UTF_8)
      )

      val t =
        for {
          _       <- rep.open
          _       <- req.open
          _       <- req.send(msg)
          readOpt <- rep.read
          _ = assert(readOpt.contains(msg))
          // FIXME Closing should be enforced via bracketing
          _ <- req.close(lingerDuration = 5.seconds)
          _ <- rep.close(lingerDuration = 5.seconds)
        } yield ()

      t.unsafeRunSync()(ioRuntime)
    }

    test("buffers") {

      val routerEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val dealerEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val port = randomPort()

      val key = Secret.randomUuid()

      val logCtx = LoggerContext.nop

      val identity = "client".getBytes(StandardCharsets.UTF_8)

      val router = ZeromqSocket(
        routerEc,
        SocketType.ROUTER,
        bind = true,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = false
      )

      val dealer = ZeromqSocket(
        dealerEc,
        SocketType.DEALER,
        bind = false,
        s"tcp://localhost:$port",
        Some(identity),
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = false
      )

      def msg(content: String, buffers: String*) =
        Message(
          Nil,
          "header".getBytes(StandardCharsets.UTF_8),
          "parent_header".getBytes(StandardCharsets.UTF_8),
          "metadata".getBytes(StandardCharsets.UTF_8),
          content.getBytes(StandardCharsets.UTF_8),
          buffers.map(_.getBytes(StandardCharsets.UTF_8))
        )

      val withBuffers    = msg("first", "buffer-1", "buffer-2")
      val withoutBuffers = msg("second")

      val t =
        for {
          _         <- router.open
          _         <- dealer.open
          _         <- dealer.send(withBuffers)
          _         <- dealer.send(withoutBuffers)
          firstOpt  <- router.read
          secondOpt <- router.read
          _ = assert(firstOpt.contains(withBuffers.copy(idents = Seq(identity.toSeq))))
          // buffers of the first message must not end up as idents of the second one
          _ = assert(secondOpt.contains(withoutBuffers.copy(idents = Seq(identity.toSeq))))
          // FIXME Closing should be enforced via bracketing
          _ <- dealer.close(lingerDuration = 5.seconds)
          _ <- router.close(lingerDuration = 5.seconds)
        } yield ()

      t.unsafeRunSync()(ioRuntime)
    }

  }

}
