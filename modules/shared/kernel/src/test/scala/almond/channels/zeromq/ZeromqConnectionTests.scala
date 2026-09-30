package almond.channels.zeromq

import java.nio.charset.StandardCharsets

import almond.channels.{Channel, ConnectionParameters, Message}
import almond.logger.LoggerContext
import almond.protocol.{Header, IopubWelcome}
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import utest._

import scala.concurrent.duration.{Deadline, DurationInt, FiniteDuration}

object ZeromqConnectionTests extends TestSuite {

  val tests = Tests {

    test("simple") {

      val logCtx        = LoggerContext.nop
      val params        = ConnectionParameters.randomLocal()
      val kernelThreads = ZeromqThreads.create("test-kernel")
      val serverThreads = ZeromqThreads.create("test-server")
      val ioRuntime     = IORuntime.global

      val msg0 = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        "content".getBytes(StandardCharsets.UTF_8)
      )

      val t =
        for {
          kernel <- params.channels(
            bind = true,
            kernelThreads,
            None,
            logCtx,
            bindToRandomPorts = false,
            identityOpt = None
          )
          server <- params.channels(
            bind = false,
            serverThreads,
            None,
            logCtx,
            bindToRandomPorts = false,
            identityOpt = None
          )
          _ <- kernel.open
          _ <- server.open
          _ <- server.send(Channel.Requests, msg0)
          resp <- kernel.tryRead(Seq(Channel.Requests), 1.second).flatMap {
            case Some(r) => IO.pure(r)
            case None    => IO.raiseError(new Exception("no message"))
          }
          _ = assert(resp.exists(_._1 == Channel.Requests))
          _ = assert(resp.exists(_._2.copy(idents = Nil) == msg0))
          // TODO Enforce this is run via bracketing
          _ <- kernel.close(partial = false, lingerDuration = 2.seconds)
          _ <- server.close(partial = false, lingerDuration = 2.seconds)
        } yield ()

      t.unsafeRunSync()(ioRuntime)
    }

    test("stdin messages are routed to the requesting client") {

      val logCtx        = LoggerContext.nop
      val params        = ConnectionParameters.randomLocal()
      val kernelThreads = ZeromqThreads.create("test-kernel")
      val clientThreads = ZeromqThreads.create("test-client")
      val ioRuntime     = IORuntime.global

      def msg(content: String) = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        content.getBytes(StandardCharsets.UTF_8)
      )

      def client(id: String) =
        params.channels(
          bind = false,
          clientThreads,
          None,
          logCtx,
          bindToRandomPorts = false,
          identityOpt = Some(id)
        )

      def read(conn: ZeromqConnection, channel: Channel, timeout: FiniteDuration) =
        conn.tryRead(Seq(channel), timeout).flatMap {
          case Some(Right((`channel`, m))) => IO.pure(Some(m))
          case Some(other) => IO.raiseError(new Exception(s"Unexpected read result: $other"))
          case None        => IO.pure(None)
        }

      def readOrFail(conn: ZeromqConnection, channel: Channel) =
        read(conn, channel, 5.seconds).flatMap {
          case Some(m) => IO.pure(m)
          case None    => IO.raiseError(new Exception(s"No message on $channel"))
        }

      def contentOf(m: Message) = new String(m.content, StandardCharsets.UTF_8)

      val t =
        for {
          kernel <- params.channels(
            bind = true,
            kernelThreads,
            None,
            logCtx,
            bindToRandomPorts = false,
            identityOpt = None
          )
          clientA <- client("client-a")
          clientB <- client("client-b")
          _       <- kernel.open
          _       <- clientA.open
          _       <- clientB.open
          // ensure both stdin connections are established before the kernel sends anything on them
          // (a ROUTER socket drops messages for peers it doesn't know yet)
          _       <- clientA.send(Channel.Input, msg("hello-a"))
          _       <- clientB.send(Channel.Input, msg("hello-b"))
          _       <- readOrFail(kernel, Channel.Input)
          _       <- readOrFail(kernel, Channel.Input)
          _       <- clientA.send(Channel.Requests, msg("execute"))
          request <- readOrFail(kernel, Channel.Requests)
          _ = assert(contentOf(request) == "execute")
          // Sending several messages, so that a DEALER socket on the kernel side, that
          // round-robins messages between peers, would send some of them to client B
          _      <- kernel.send(Channel.Input, msg("input-1").copy(idents = request.idents))
          _      <- kernel.send(Channel.Input, msg("input-2").copy(idents = request.idents))
          input1 <- readOrFail(clientA, Channel.Input)
          input2 <- readOrFail(clientA, Channel.Input)
          fromB  <- read(clientB, Channel.Input, 500.millis)
          _ = assert(contentOf(input1) == "input-1")
          _ = assert(contentOf(input2) == "input-2")
          _ = assert(input1.idents.isEmpty)
          _ = assert(fromB.isEmpty)
          _ <- kernel.close(partial = false, lingerDuration = 2.seconds)
          _ <- clientA.close(partial = false, lingerDuration = 2.seconds)
          _ <- clientB.close(partial = false, lingerDuration = 2.seconds)
        } yield ()

      t.unsafeRunSync()(ioRuntime)
    }

    test("iopub welcome") {

      val logCtx        = LoggerContext.nop
      val params        = ConnectionParameters.randomLocal()
      val kernelThreads = ZeromqThreads.create("test-kernel")
      val serverThreads = ZeromqThreads.create("test-server")
      val ioRuntime     = IORuntime.global

      def welcome(
        kernel: ZeromqConnection,
        server: ZeromqConnection,
        deadline: Deadline
      ): IO[Message] =
        // kernel only handles subscriptions when reading from its channels
        kernel.tryRead(Channel.channels, 100.millis) *>
          server.tryRead(Seq(Channel.Publish), 100.millis).flatMap {
            case Some(Right((_, msg))) => IO.pure(msg)
            case Some(Left(()))        => IO.raiseError(new Exception("connection closed"))
            case None if deadline.isOverdue() =>
              IO.raiseError(new Exception("no iopub_welcome message"))
            case None => welcome(kernel, server, deadline)
          }

      val t =
        for {
          kernel <- ZeromqConnection(
            params,
            bind = true,
            None,
            kernelThreads,
            None,
            logCtx,
            bindToRandomPorts = false
          )
          server <- ZeromqConnection(
            params,
            bind = false,
            None,
            serverThreads,
            None,
            logCtx,
            bindToRandomPorts = false
          )
          _   <- kernel.open
          _   <- server.open
          msg <- welcome(kernel, server, 10.seconds.fromNow)
          header  = readFromArray(msg.header)(Header.codec)
          content = readFromArray(msg.content)(IopubWelcome.codec)
          _       = assert(header.msg_type == IopubWelcome.messageType.messageType)
          _       = assert(header.version.contains("5.5"))
          _       = assert(content.subscription == "")
          _       = assert(new String(msg.parentHeader, StandardCharsets.UTF_8) == "{}")
          _ <- kernel.close(partial = false, lingerDuration = 2.seconds)
          _ <- server.close(partial = false, lingerDuration = 2.seconds)
        } yield ()

      t.unsafeRunSync()(ioRuntime)
    }

  }

}
