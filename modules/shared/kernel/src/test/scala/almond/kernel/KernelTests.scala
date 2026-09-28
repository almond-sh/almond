package almond.kernel

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import almond.channels.Channel
import almond.interpreter.messagehandlers.MessageHandler
import almond.interpreter.{Message, TestInterpreter}
import almond.interpreter.TestInterpreter.StringBOps
import almond.logger.LoggerContext
import almond.protocol.{
  Comm,
  Complete,
  Execute,
  Header,
  History,
  Input,
  KernelInfo,
  RawJson,
  Shutdown,
  Status
}
import almond.protocol.Codecs.unitCodec
import almond.testkit.ClientStreams
import almond.util.ThreadUtil.{
  attemptShutdownExecutionContext,
  singleThreadedExecutionContextExecutorService
}
import cats.effect.IO
import fs2.Stream
import utest._

import scala.concurrent.duration.DurationInt

object KernelTests extends TestSuite {

  val logCtx = LoggerContext.nop // debug: LoggerContext.stderr(almond.logger.Level.Debug)

  val interpreterEc  = singleThreadedExecutionContextExecutorService("test-interpreter")
  val cancellablesEc = singleThreadedExecutionContextExecutorService("test-cancellables")

  val threads = KernelThreads.create("test")

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }

  /** Statuses published by the kernel while processing the request with id `parentMsgId` */
  def statuses(streams: ClientStreams, parentMsgId: String): Seq[String] =
    streams.generatedMessages.toVector.collect {
      case Left((Channel.Publish, m))
          if m.header.msg_type == Status.messageType.messageType &&
          m.parent_header.exists(_.msg_id == parentMsgId) =>
        com.github.plokhotnyuk.jsoniter_scala.core
          .readFromArray(m.content.value)(Status.codec)
          .execution_state
    }

  val tests = Tests {

    test("stdin") {

      // These describe how the pseudo-client reacts to incoming messages - it answers input_request, and
      // ignores stuff on the publish channel

      val inputHandler = MessageHandler(Channel.Input, Input.requestType) { msg =>

        val resp = Input.Reply("> " + msg.content.prompt)

        msg
          .clearParentHeader // leave parent_header empty, like the jupyter UI does (rather than filling it from the input_request message)
          .clearMetadata
          .update(Input.replyType, resp)
          .streamOn(Channel.Input)
      }

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _)                                          =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply" =>
      }

      // we stop the pseudo-client at the first execute_reply

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply")

      // initial request from client, that triggers the rest

      val input =
        Message(
          Header.random("test", Execute.requestType),
          Execute.Request("input:foo")
        ).streamOn(Channel.Requests)

      val streams =
        ClientStreams.create(
          input,
          stopWhen,
          inputHandler.orElse(ignoreExpectedReplies),
          threads.ioRuntime
        )

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, cancellablesEc, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(2.seconds)(threads.ioRuntime)
      assert(res.nonEmpty)

      val inputReply   = streams.singleRequest(Channel.Input, Input.replyType)
      val inputRequest = streams.singleReply(Channel.Input, Input.requestType)

      assert(inputRequest.content.prompt == "foo")
      assert(!inputRequest.content.password)
      assert(inputReply.content.value == "> foo")
    }

    test("client comm") {

      val sessionId  = UUID.randomUUID().toString
      val exitHeader = Header.random("test", Execute.requestType, sessionId)

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(
            m.header.msg_type == "execute_reply" &&
            m.parent_header.exists(_.msg_id == exitHeader.msg_id)
          )

      val input = Stream(
        Message(
          Header.random("test", Execute.requestType, sessionId),
          Execute.Request("comm-open:foo")
        ).on(Channel.Requests),
        Message(
          Header.random("test", Execute.requestType, sessionId),
          Execute.Request("comm-message:foo")
        ).on(Channel.Requests),
        Message(
          Header.random("test", Execute.requestType, sessionId),
          Execute.Request("comm-close:foo")
        ).on(Channel.Requests),
        Message(
          exitHeader,
          Execute.Request("echo:exit")
        ).on(Channel.Requests)
      )

      val streams = ClientStreams.create(input, stopWhen, ioRuntime = threads.ioRuntime)

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, cancellablesEc, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(10.seconds)(threads.ioRuntime)
      assert(res.nonEmpty)

      val msgTypes = streams.generatedMessageTypes()

      val expectedMsgTypes = Seq(
        // FIXME The execute_input should be sent prior to the comm_* (that is before the code is actually run)
        "comm_open",
        "execute_input",
        "execute_reply",
        "comm_msg",
        "execute_input",
        "execute_reply",
        "comm_close",
        "execute_input",
        "execute_reply",
        "execute_input",
        "execute_result",
        "execute_reply"
      )

      val (commMsgTypes, stdMsgTypes) = msgTypes.partition(_.startsWith("comm_"))
      val (expectedCommMsgTypes, expectedStdMsgTypes) =
        expectedMsgTypes.partition(_.startsWith("comm_"))

      assert(commMsgTypes == expectedCommMsgTypes)
      assert(stdMsgTypes == expectedStdMsgTypes)
    }

    test("comm_open for unknown target gets comm_close on IOPub") {

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (channel, m) =>
          IO.pure(
            channel == Channel.Publish &&
            m.header.msg_type == Status.messageType.messageType &&
            m.parent_header.exists(_.msg_type == Comm.openType.messageType) &&
            m.decodeAs[Status].toOption.exists(_.content == Status.idle)
          )

      val sessionId = UUID.randomUUID().toString
      val commId    = UUID.randomUUID().toString
      val input = Stream(
        Message(
          Header.random("test", Comm.openType, sessionId),
          Comm.Open(commId, "unknown-target", RawJson.emptyObj)
        ).on(Channel.Requests)
      )

      val streams = ClientStreams.create(input, stopWhen, ioRuntime = threads.ioRuntime)

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, cancellablesEc, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(10.seconds)(threads.ioRuntime)
      assert(res.nonEmpty)

      assert(streams.generatedMessageTypes(channels = Set(Channel.Requests)).isEmpty)

      val close = streams.singleReply(Channel.Publish, Comm.closeType)
      assert(close.content.comm_id == commId)
      assert(close.parent_header.exists(_.msg_type == Comm.openType.messageType))
    }

    test("stop on error") {

      def replyStatuses(stopOnError: Option[Boolean]): Seq[String] = {

        val replyCount = new AtomicInteger
        val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
          (_, m) =>
            IO {
              m.header.msg_type == "execute_reply" && replyCount.incrementAndGet() == 3
            }

        val sessionId = UUID.randomUUID().toString
        val input = Stream("error-after:200", "echo:foo", "echo:bar").map { code =>
          Message(
            Header.random("test", Execute.requestType, sessionId),
            Execute.Request(code, stop_on_error = stopOnError)
          ).on(Channel.Requests)
        }

        val streams = ClientStreams.create(input, stopWhen, ioRuntime = threads.ioRuntime)

        val t = Kernel.create(new TestInterpreter, interpreterEc, threads, cancellablesEc, logCtx)
          .flatMap(_.run(streams.source, streams.sink, Nil))

        val res = t.unsafeRunTimed(10.seconds)(threads.ioRuntime)
        assert(res.nonEmpty)

        streams.generatedMessages.toList.collect {
          case Left((Channel.Requests, m)) if m.header.msg_type == Execute.replyType.messageType =>
            m.decodeAs[Execute.Reply] match {
              case Left(err) => throw new Exception(s"Error decoding execute_reply: $err")
              case Right(m0) =>
                m0.content match {
                  case _: Execute.Reply.Success => "ok"
                  case _: Execute.Reply.Error   => "error"
                  case _: Execute.Reply.Abort   => "abort"
                }
            }
        }
      }

      test("default") {
        // stop_on_error defaults to true when absent, per the Jupyter messaging spec
        val statuses = replyStatuses(None)
        assert(statuses == Seq("error", "abort", "abort"))
      }

      test("enabled") {
        val statuses = replyStatuses(Some(true))
        assert(statuses == Seq("error", "abort", "abort"))
      }

      test("disabled") {
        val statuses = replyStatuses(Some(false))
        assert(statuses == Seq("error", "ok", "ok"))
      }
    }

    test("history request") {

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) => IO.pure(m.header.msg_type == "history_reply")

      val sessionId = UUID.randomUUID().toString
      val input = Stream(
        Message(
          Header.random("test", History.requestType, sessionId),
          History.Request(output = false, raw = false, History.AccessType.Range)
        ).on(Channel.Requests)
      )

      val streams = ClientStreams.create(input, stopWhen, ioRuntime = threads.ioRuntime)

      val interpreter = new TestInterpreter
      val t = Kernel.create(interpreter, interpreterEc, threads, cancellablesEc, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(10.seconds)(threads.ioRuntime)
      assert(res.nonEmpty)

      val msgTypes = streams.generatedMessageTypes()

      val expectedMsgTypes = Seq(History.replyType.messageType)

      assert(msgTypes == expectedMsgTypes)
    }

    test("kernel info request on control channel") {

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (channel, m) =>
          IO.pure(channel == Channel.Control && m.header.msg_type == "kernel_info_reply")

      val request = Message(
        Header.random("test", KernelInfo.requestType),
        ()
      )
      val input = request.streamOn(Channel.Control)

      val streams = ClientStreams.create(input, stopWhen, ioRuntime = threads.ioRuntime)

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, cancellablesEc, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(10.seconds)(threads.ioRuntime)
      assert(res.nonEmpty)

      val reply = streams.singleReply(Channel.Control, KernelInfo.replyType)
      assert(reply.content.implementation == "test")

      val statuses0 = statuses(streams, request.header.msg_id)
      assert(statuses0 == Seq("busy", "idle"))
    }

    def shutdownRequestTest(channel: Channel): Unit = {

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, _) =>
          IO.pure(false)

      val sessionId = UUID.randomUUID().toString
      val request = Message(
        Header.random("test", Shutdown.requestType, sessionId),
        Shutdown.Request(restart = false)
      )
      val input = request.streamOn(channel)

      val streams = ClientStreams.create(input, stopWhen, ioRuntime = threads.ioRuntime)

      val interpreter = new TestInterpreter
      val t = Kernel.create(interpreter, interpreterEc, threads, cancellablesEc, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(10.seconds)(threads.ioRuntime)
      assert(res.nonEmpty)

      assert(interpreter.shutdownCalled())

      val msgTypes = streams.generatedMessageTypes(Set(Channel.Publish, channel))

      val expectedMsgTypes = Seq(Shutdown.replyType.messageType)

      assert(msgTypes == expectedMsgTypes)

      val reply = streams.singleReply(channel, Shutdown.replyType)
      assert(!reply.content.restart)

      val statuses0 = statuses(streams, request.header.msg_id)
      assert(statuses0 == Seq("busy", "idle"))
    }

    test("shutdown request") {
      shutdownRequestTest(Channel.Requests)
    }

    test("shutdown request on control channel") {
      shutdownRequestTest(Channel.Control)
    }

    test("completion metadata") {

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _)                                           =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply"  =>
        case (Channel.Requests, m) if m.header.msg_type == "complete_reply" =>
      }

      // we stop the pseudo-client at the first execute_reply

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply")

      val rawMetadata = """{ "a": 2, "b": [true, false, "s"] }"""

      val sessionId = UUID.randomUUID().toString
      val input = Stream(
        Message(
          Header.random("test", Complete.requestType, sessionId),
          Complete.Request(s"meta:$rawMetadata", 5)
        ).on(Channel.Requests),
        Message(
          Header.random("test", Execute.requestType, sessionId),
          Execute.Request("echo:foo")
        ).on(Channel.Requests)
      )

      val streams =
        ClientStreams.create(input, stopWhen, ignoreExpectedReplies, ioRuntime = threads.ioRuntime)

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, cancellablesEc, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(2.seconds)(threads.ioRuntime)
      assert(res.nonEmpty)

      val msgTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toSet

      // Using a set, as these may be processed concurrently
      val expectedMsgTypes = Set(
        Complete.replyType.messageType,
        Execute.replyType.messageType
      )

      assert(msgTypes == expectedMsgTypes)

      val completeReply    = streams.singleReply(Channel.Requests, Complete.replyType)
      val metadata         = completeReply.content.metadata
      val expectedMetadata = RawJson(rawMetadata.bytes)

      assert(metadata == expectedMetadata)
    }

  }

}
