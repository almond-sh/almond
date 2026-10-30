package almond.channels.zeromq

import java.util.concurrent.{ConcurrentLinkedQueue, Executors}

import almond.channels.{ConnectionParameters, Message}
import almond.logger.LoggerContext
import almond.util.Secret
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.zeromq.{SocketType, ZMQ}
import utest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import java.nio.charset.StandardCharsets

object ZeromqSocketTests extends TestSuite {

  private val ctx       = ZMQ.context(4)
  private val ioRuntime = IORuntime.global

  private val sockets = new ConcurrentLinkedQueue[ZeromqSocket]

  private def tracked(socket: ZeromqSocket): ZeromqSocket = {
    sockets.add(socket)
    socket
  }

  override def utestAfterAll() = {
    // ctx.term() waits for all the sockets of ctx to be closed, so closing the ones tests
    // may have left open, rather than having it hang
    closeAll(sockets.asScala.toSeq: _*).unsafeRunSync()(ioRuntime)
    ctx.term()
  }

  // Closing sockets even if the test fails, as ctx.term() in utestAfterAll waits for all
  // the sockets of ctx to be closed
  private def closeAll(sockets: ZeromqSocket*): IO[Unit] =
    sockets.foldLeft(IO.unit) { (acc, socket) =>
      acc *> socket.close(lingerDuration = 5.seconds).attempt.void
    }

  val tests = Tests {

    test("signatureForLog") {
      val signature = "0123456789abcdef" * 4
      val logged    = ZeromqSocketImpl.signatureForLog(signature)
      assert(logged == "'01234567...' (64 chars)")

      val malicious = ZeromqSocketImpl.signatureForLog("a\n\u001b[1'\\b")
      assert(malicious == "'a\\u000a\\u001b[1\\'\\\\b' (8 chars)")

      assert(ZeromqSocketImpl.signatureForLog("") == "'' (0 chars)")
      assert(ZeromqSocketImpl.signatureForLog(null) == "no signature")
    }

    test("simple") {

      val repEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val reqEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val key = Secret.randomUuid()

      val logCtx = LoggerContext.nop

      val rep = tracked(ZeromqSocket(
        repEc,
        SocketType.REP,
        bind = true,
        "tcp://localhost",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = true,
        maxMessageSize = Some(ZeromqThreads.defaultMaxMessageSize)
      ))

      def newReq(port: Int) = tracked(ZeromqSocket(
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
        bindToRandomPort = false,
        maxMessageSize = Some(ZeromqThreads.defaultMaxMessageSize)
      ))

      val msg = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        "content".getBytes(StandardCharsets.UTF_8)
      )

      val t =
        rep.open.map(_.getOrElse(sys.error("No port picked"))).flatMap { port =>
          val req = newReq(port)
          val t0  =
            for {
              _       <- req.open
              _       <- req.send(msg)
              readOpt <- rep.read
              _ = assert(readOpt.contains(msg))
            } yield ()
          t0.guarantee(closeAll(req))
        }

      t.guarantee(closeAll(rep)).unsafeRunSync()(ioRuntime)
    }

    test("simpleWithNoKey") {

      val repEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val reqEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val key = Secret("") // having no key disables signature checking

      val logCtx = LoggerContext.nop

      val rep = tracked(ZeromqSocket(
        repEc,
        SocketType.REP,
        bind = true,
        "tcp://localhost",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = true,
        maxMessageSize = Some(ZeromqThreads.defaultMaxMessageSize)
      ))

      def newReq(port: Int) = tracked(ZeromqSocket(
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
        bindToRandomPort = false,
        maxMessageSize = Some(ZeromqThreads.defaultMaxMessageSize)
      ))

      val msg = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        "content".getBytes(StandardCharsets.UTF_8)
      )

      val t =
        rep.open.map(_.getOrElse(sys.error("No port picked"))).flatMap { port =>
          val req = newReq(port)
          val t0  =
            for {
              _       <- req.open
              _       <- req.send(msg)
              readOpt <- rep.read
              _ = assert(readOpt.contains(msg))
            } yield ()
          t0.guarantee(closeAll(req))
        }

      t.guarantee(closeAll(rep)).unsafeRunSync()(ioRuntime)
    }

    test("buffers") {

      val routerEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val dealerEc = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor()
      )
      val key = Secret.randomUuid()

      val logCtx = LoggerContext.nop

      val identity = "client".getBytes(StandardCharsets.UTF_8)

      val router = tracked(ZeromqSocket(
        routerEc,
        SocketType.ROUTER,
        bind = true,
        "tcp://localhost",
        None,
        None,
        ctx,
        key,
        "hmac-sha256",
        None,
        logCtx,
        bindToRandomPort = true,
        maxMessageSize = Some(ZeromqThreads.defaultMaxMessageSize)
      ))

      def newDealer(port: Int) = tracked(ZeromqSocket(
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
        bindToRandomPort = false,
        maxMessageSize = Some(ZeromqThreads.defaultMaxMessageSize)
      ))

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
        router.open.map(_.getOrElse(sys.error("No port picked"))).flatMap { port =>
          val dealer = newDealer(port)
          val t0     =
            for {
              _         <- dealer.open
              _         <- dealer.send(withBuffers)
              _         <- dealer.send(withoutBuffers)
              firstOpt  <- router.read
              secondOpt <- router.read
              _ = assert(firstOpt.contains(withBuffers.copy(idents = Seq(identity.toSeq))))
              // buffers of the first message must not end up as idents of the second one
              _ = assert(secondOpt.contains(withoutBuffers.copy(idents = Seq(identity.toSeq))))
            } yield ()
          t0.guarantee(closeAll(dealer))
        }

      t.guarantee(closeAll(router)).unsafeRunSync()(ioRuntime)
    }

    test("parse frames") {

      def frames(strings: String*): Seq[Array[Byte]] =
        strings.map(_.getBytes(StandardCharsets.UTF_8))

      def str(b: Array[Byte]) = new String(b, StandardCharsets.UTF_8)

      test("valid") {
        val res = ZeromqSocketImpl.parseFrames(
          frames("id-1", "id-2", "<IDS|MSG>", "sig", "h", "p", "m", "c", "buf-1", "buf-2")
        )
        val (message, signature) = res.fold(err => sys.error(err), identity)
        assert(signature == "sig")
        assert(message.idents.map(b => str(b.toArray)) == Seq("id-1", "id-2"))
        assert(str(message.header) == "h")
        assert(str(message.parentHeader) == "p")
        assert(str(message.metadata) == "m")
        assert(str(message.content) == "c")
        assert(message.buffers.map(str) == Seq("buf-1", "buf-2"))
      }

      test("no delimiter") {
        assert(ZeromqSocketImpl.parseFrames(frames("junk")).isLeft)
        assert(ZeromqSocketImpl.parseFrames(frames("id", "sig", "h", "p", "m", "c")).isLeft)
      }

      test("too few frames") {
        assert(ZeromqSocketImpl.parseFrames(frames("id", "<IDS|MSG>")).isLeft)
        assert(ZeromqSocketImpl.parseFrames(frames("id", "<IDS|MSG>", "sig", "h", "p", "m")).isLeft)
      }

      test("null frame") {
        val frames0 = frames("id", "<IDS|MSG>", "sig", "h", "p", "m", "c") :+ (null: Array[Byte])
        assert(ZeromqSocketImpl.parseFrames(frames0).isLeft)
      }
    }

  }

}
