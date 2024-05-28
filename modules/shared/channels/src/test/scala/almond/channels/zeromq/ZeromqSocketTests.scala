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

  private val ctx = ZMQ.context(4)

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
        logCtx
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
        logCtx
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

      t.unsafeRunSync()(IORuntime.global)
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
        logCtx
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
        logCtx
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

      t.unsafeRunSync()(IORuntime.global)
    }

  }

}
