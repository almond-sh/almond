package almond.kernel

import java.nio.charset.StandardCharsets
import java.util.UUID

import almond.channels.Channel
import almond.interpreter.messagehandlers.MessageHandler
import almond.interpreter.{Message, TestInterpreter}
import almond.interpreter.TestInterpreter.StringBOps
import almond.logger.LoggerContext
import almond.protocol.{Complete, Execute, Header, History, Input, RawJson, Shutdown}
import almond.testkit.ClientStreams
import almond.util.ThreadUtil.{attemptShutdownExecutionContext, singleThreadedExecutionContext}
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import utest._

import scala.concurrent.duration.DurationInt

object KernelTests extends TestSuite {

  val logCtx = LoggerContext.nop // debug: LoggerContext.stderr(almond.logger.Level.Debug)

  val interpreterEc = singleThreadedExecutionContext("test-interpreter")

  val threads = KernelThreads.create("test")

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
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
        ClientStreams.create(input, stopWhen, inputHandler.orElse(ignoreExpectedReplies))

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(2.seconds)(IORuntime.global)
      assert(res.nonEmpty)

      val inputReply   = streams.singleRequest(Channel.Input, Input.replyType)
      val inputRequest = streams.singleReply(Channel.Input, Input.requestType)

      assert(inputRequest.content.prompt == "foo")
      assert(!inputRequest.content.password)
      assert(inputReply.content.value == "> foo")
    }

    test("client comm") {

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && new String(
            m.content.value,
            StandardCharsets.UTF_8
          ).contains("exit"))

      val sessionId = UUID.randomUUID().toString
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
          Header.random("test", Execute.requestType, sessionId),
          Execute.Request("echo:exit")
        ).on(Channel.Requests)
      )

      val streams = ClientStreams.create(input, stopWhen)

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(10.seconds)(IORuntime.global)
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

      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new TestInterpreter
      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(10.seconds)(IORuntime.global)
      assert(res.nonEmpty)

      val msgTypes = streams.generatedMessageTypes()

      val expectedMsgTypes = Seq(History.replyType.messageType)

      assert(msgTypes == expectedMsgTypes)
    }

    test("shutdown request") {

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, _) =>
          IO.pure(false)

      val sessionId = UUID.randomUUID().toString
      val input = Stream(
        Message(
          Header.random("test", Shutdown.requestType, sessionId),
          Shutdown.Request(restart = false)
        ).on(Channel.Requests)
      )

      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new TestInterpreter
      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(10.seconds)(IORuntime.global)
      assert(res.nonEmpty)

      assert(interpreter.shutdownCalled())

      val msgTypes = streams.generatedMessageTypes()

      val expectedMsgTypes = Seq(Shutdown.replyType.messageType)

      assert(msgTypes == expectedMsgTypes)
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

      val streams = ClientStreams.create(input, stopWhen, ignoreExpectedReplies)

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink, Nil))

      val res = t.unsafeRunTimed(2.seconds)(IORuntime.global)
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
