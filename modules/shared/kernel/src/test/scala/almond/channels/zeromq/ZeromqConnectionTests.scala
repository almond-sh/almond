package almond.channels.zeromq

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

import almond.channels.{Channel, ConnectionParameters, Message}
import almond.logger.LoggerContext
import almond.protocol.{Header, IopubWelcome}
import cats.effect.{Deferred, IO}
import cats.effect.unsafe.IORuntime
import cats.syntax.all._
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import org.zeromq.{SocketType, ZMQ}
import utest._

import scala.concurrent.duration.{Deadline, Duration, DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters._

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

    test("stuck channel doesn't hold up the others") {

      val logCtx        = LoggerContext.nop
      val params        = ConnectionParameters.randomLocal()
      val kernelThreads = ZeromqThreads.create("test-kernel")
      val clientThreads = ZeromqThreads.create("test-client")
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
          client <- params.channels(
            bind = false,
            clientThreads,
            None,
            logCtx,
            bindToRandomPorts = false,
            identityOpt = None
          )
          _        <- kernel.open
          _        <- client.open
          received <- Deferred[IO, (Channel, Message)]
          fiber <- kernel.stream()
            .evalMap(received.complete)
            .take(1)
            .compile
            .drain
            .start
          // blocks the thread of the shell socket, like a read stuck on it would
          shellLatch = new CountDownLatch(1)
          _ <- IO(kernelThreads.channelEces(Channel.Requests).execute(() => shellLatch.await()))
          _ <- IO.sleep(1.second)
          _ <- client.send(Channel.Control, msg0)
          res <- received.get.timeoutTo(
            10.seconds,
            IO.raiseError(new Exception("Message on control held up by stuck shell channel"))
          )
          _ = assert(res._1 == Channel.Control)
          _ = assert(res._2.copy(idents = Nil) == msg0)
          _ <- IO(shellLatch.countDown())
          _ <- fiber.joinWithNever
          _ <- kernel.close(partial = false, lingerDuration = 2.seconds)
          _ <- client.close(partial = false, lingerDuration = 2.seconds)
        } yield ()

      t.unsafeRunSync()(ioRuntime)
    }

    test("malformed messages are dropped") {

      val logCtx        = LoggerContext.nop
      val params        = ConnectionParameters.randomLocal()
      val kernelThreads = ZeromqThreads.create("test-kernel")
      val clientThreads = ZeromqThreads.create("test-client")
      val ioRuntime     = IORuntime.global

      val msg0 = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        "content".getBytes(StandardCharsets.UTF_8)
      )

      val rawIdentity    = "raw".getBytes(StandardCharsets.UTF_8)
      val clientIdentity = "client"

      def rawShellSocket(): ZMQ.Socket = {
        val socket = clientThreads.context.socket(SocketType.DEALER)
        socket.setLinger(0)
        socket.setIdentity(rawIdentity)
        socket.connect(params.uri(Channel.Requests))
        socket
      }

      def readShell(kernel: ZeromqConnection, deadline: Deadline): IO[Message] =
        kernel.tryRead(Seq(Channel.Requests), 100.millis).flatMap {
          case Some(Right((_, m))) => IO.pure(m)
          case Some(Left(()))      => IO.raiseError(new Exception("connection closed"))
          case None if deadline.isOverdue() =>
            IO.raiseError(new Exception("No message on shell"))
          case None => readShell(kernel, deadline)
        }

      // Sends a malformed message, has the kernel start reading while it's the only pending
      // message, then sends a valid message
      def check(
        kernel: ZeromqConnection,
        client: ZeromqConnection,
        malformedFrames: Seq[String]
      ): IO[Unit] =
        for {
          raw <- IO(rawShellSocket())
          _ <- IO {
            for (frame <- malformedFrames.init)
              raw.sendMore(frame)
            raw.send(malformedFrames.last)
          }
          _       <- IO.sleep(500.millis)
          reading <- readShell(kernel, 10.seconds.fromNow).start
          _       <- IO.sleep(500.millis)
          _       <- client.send(Channel.Requests, msg0)
          message <- reading.joinWithNever
          // the frames of the malformed message mustn't end up in the idents of the valid one,
          // or replies would be routed to the peer that sent them
          _ = assert(
            message.idents.map(_.toVector) ==
              Seq(clientIdentity.getBytes(StandardCharsets.UTF_8).toVector)
          )
          _ = assert(message.copy(idents = Nil) == msg0)
          _ <- IO(raw.close())
        } yield ()

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
          client <- params.channels(
            bind = false,
            clientThreads,
            None,
            logCtx,
            bindToRandomPorts = false,
            identityOpt = Some(clientIdentity)
          )
          _ <- kernel.open
          _ <- client.open
          // lone frame, without "<IDS|MSG>" delimiter
          _ <- check(kernel, client, Seq("junk"))
          // too few frames after the delimiter
          _ <- check(kernel, client, Seq("<IDS|MSG>", "signature", "{}"))
          _ <- kernel.close(partial = false, lingerDuration = 2.seconds)
          _ <- client.close(partial = false, lingerDuration = 2.seconds)
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

    test("publish while polling") {

      // Sends many messages on the publish socket, while channels are polled, and clients
      // keep subscribing and unsubscribing (which sends commands to the publish socket).
      // JeroMQ sockets aren't thread-safe, and these used to be accessed concurrently by the
      // polling thread and the publish thread, which could leave the latter spinning forever
      // in zmq.Signaler.recv (https://github.com/almond-sh/almond/issues/549).

      val logCtx        = LoggerContext.nop
      val params        = ConnectionParameters.randomLocal()
      val kernelThreads = ZeromqThreads.create("test-kernel")
      val ioRuntime     = IORuntime.global

      // can be increased to run this test longer, when trying to reproduce issues
      val duration = sys.props.get("almond.test.publish-while-polling.duration")
        .map(Duration(_))
        .collect { case d: FiniteDuration => d }
        .getOrElse(5.seconds)
      val stallTimeout = 10.seconds
      val subscribers  = 4

      def msg(idx: Long) = Message(
        Nil,
        "header".getBytes(StandardCharsets.UTF_8),
        "parent_header".getBytes(StandardCharsets.UTF_8),
        "metadata".getBytes(StandardCharsets.UTF_8),
        s"content-$idx".getBytes(StandardCharsets.UTF_8)
      )

      @volatile var done = false
      val sent           = new AtomicLong

      def subscriberThread(idx: Int) =
        new Thread(s"test-subscriber-$idx") {
          setDaemon(true)
          override def run(): Unit = {
            val socket = kernelThreads.context.socket(SocketType.SUB)
            try {
              socket.setLinger(0)
              socket.connect(params.uri(Channel.Publish))
              val topic = s"topic-$idx".getBytes(StandardCharsets.UTF_8)
              while (!done) {
                socket.subscribe(topic)
                socket.unsubscribe(topic)
              }
            }
            finally socket.close()
          }
        }

      def zeromqStacks(): String =
        Thread.getAllStackTraces.asScala.toVector
          .filter(_._1.getName.startsWith("test-kernel-zeromq-"))
          .map {
            case (thread, stack) =>
              (s"${thread.getName} (${thread.getState})" +: stack.toVector.take(15).map("  " + _))
                .mkString(System.lineSeparator())
          }
          .mkString(System.lineSeparator())

      // fails if no message could be sent during stallTimeout
      def watchdog(lastCount: Long, lastProgress: Deadline): IO[Unit] =
        IO.sleep(500.millis) *> IO(sent.get()).flatMap { count =>
          if (count != lastCount) watchdog(count, Deadline.now)
          else if (Deadline.now - lastProgress > stallTimeout)
            IO.raiseError(new Exception(
              s"Publishing stalled after $count messages:" + System.lineSeparator() + zeromqStacks()
            ))
          else watchdog(lastCount, lastProgress)
        }

      def sendLoop(kernel: ZeromqConnection, deadline: Deadline): IO[Unit] =
        IO.defer {
          if (deadline.isOverdue()) IO.unit
          else
            kernel.send(Channel.Publish, msg(sent.get())) *>
              IO(sent.incrementAndGet()) *>
              sendLoop(kernel, deadline)
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
          _ <- kernel.open
          poll = kernel
            .tryRead(Channel.channels, 1.millis)
            .flatMap {
              case Some(Left(())) => IO.pure(true)
              case _              => IO(done)
            }
            .iterateUntil(identity)
          polling <- poll.start
          threads = (1 to subscribers).map(subscriberThread)
          _ <- IO(threads.foreach(_.start()))
          // the send loop can't be cancelled if it hangs in JeroMQ, so we don't wait for it
          // when the watchdog fails
          res <- IO.race(watchdog(0L, Deadline.now), sendLoop(kernel, duration.fromNow)).attempt
          _   <- IO { done = true }
          _ <- res match {
            case Left(e) => IO.raiseError(e)
            case Right(_) =>
              IO.blocking(threads.foreach(_.join())) *>
                polling.joinWithNever *>
                kernel.close(false, 2.seconds)
          }
        } yield ()

      t.unsafeRunSync()(ioRuntime)
      assert(sent.get() > 0L)
    }

  }

}
