package almond.channels.zeromq

import java.util.concurrent.Executors

import almond.channels.{ConnectionParameters, Message}
import almond.logger.LoggerContext
import almond.util.Secret
import org.zeromq.ZMQ
import utest._

import scala.concurrent.ExecutionContext

object ZeromqSocketTests extends TestSuite {

  private val ctx = ZMQ.context(4)

  override def utestAfterAll() = {
    ctx.term()
  }

  val tests = Tests {

    'simple - {

      val repEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val reqEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val port = ConnectionParameters.randomPort()

      val key = Secret.randomUuid()

      val logCtx = LoggerContext.nop

      val rep = ZeromqSocket(
        repEc,
        ZMQ.REP,
        bind = true,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        logCtx
      )

      val req = ZeromqSocket(
        reqEc,
        ZMQ.REQ,
        bind = false,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        logCtx
      )

      val msg = Message(
        Nil,
        "header",
        "parent_header",
        "metadata",
        "content"
      )

      val t =
        for {
          _ <- rep.open
          _ <- req.open
          _ <- req.send(msg)
          readOpt <- rep.read
          _ = assert(readOpt.contains(msg))
          // FIXME Closing should be enforced via bracketing
          _ <- req.close
          _ <- rep.close
        } yield ()

      t.unsafeRunSync()
    }

    'simpleWithNoKey - {

      val repEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val reqEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val port = ConnectionParameters.randomPort()

      val key = Secret("") // having no key disables signature checking

      val logCtx = LoggerContext.nop

      val rep = ZeromqSocket(
        repEc,
        ZMQ.REP,
        bind = true,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        logCtx
      )

      val req = ZeromqSocket(
        reqEc,
        ZMQ.REQ,
        bind = false,
        s"tcp://localhost:$port",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        logCtx
      )

      val msg = Message(
        Nil,
        "header",
        "parent_header",
        "metadata",
        "content"
      )

      val t =
        for {
          _ <- rep.open
          _ <- req.open
          _ <- req.send(msg)
          readOpt <- rep.read
          _ = assert(readOpt.contains(msg))
          // FIXME Closing should be enforced via bracketing
          _ <- req.close
          _ <- rep.close
        } yield ()

      t.unsafeRunSync()
    }

  }

}
