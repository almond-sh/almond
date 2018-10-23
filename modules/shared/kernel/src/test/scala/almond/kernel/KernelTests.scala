package almond.kernel

import java.util.UUID

import almond.channels.Channel
import almond.interpreter.messagehandlers.MessageHandler
import almond.interpreter.util.BetterPrinter
import almond.interpreter.{Message, TestInterpreter}
import almond.logger.LoggerContext
import almond.protocol.{Execute, Header, History, Input, Shutdown}
import almond.util.ThreadUtil.{attemptShutdownExecutionContext, singleThreadedExecutionContext}
import argonaut.Json
import cats.effect.IO
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

    "stdin" - {

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
        case (Channel.Publish, _) =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply" =>
      }

      // we stop the pseudo-client at the first execute_reply

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply")

      // initial request from client, that triggers the rest

      val input =
        Message(
          Header.random("test", Execute.requestType),
          Execute.Request("input:foo")
        ).streamOn(Channel.Requests)


      val streams = ClientStreams.create(input, stopWhen, inputHandler.orElse(ignoreExpectedReplies))

      val t = Kernel.create(new TestInterpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      val res = t.unsafeRunTimed(2.seconds)
      assert(res.nonEmpty)

      val inputReply = streams.singleRequest(Channel.Input, Input.replyType)
      val inputRequest = streams.singleReply(Channel.Input, Input.requestType)

      assert(inputRequest.content.prompt == "foo")
      assert(!inputRequest.content.password)
      assert(inputReply.content.value == "> foo")
    }

    "client comm" - {

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && BetterPrinter.noSpaces(m.content).contains("exit"))

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
        .flatMap(_.run(streams.source, streams.sink))

      val res = t.unsafeRunTimed(10.seconds)
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

      assert(msgTypes == expectedMsgTypes)
    }

    "history request" - {

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
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
        .flatMap(_.run(streams.source, streams.sink))

      val res = t.unsafeRunTimed(10.seconds)
      assert(res.nonEmpty)

      val msgTypes = streams.generatedMessageTypes()

      val expectedMsgTypes = Seq(History.replyType.messageType)

      assert(msgTypes == expectedMsgTypes)
    }

    "shutdown request" - {

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
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
        .flatMap(_.run(streams.source, streams.sink))

      val res = t.unsafeRunTimed(10.seconds)
      assert(res.nonEmpty)

      assert(interpreter.shutdownCalled())

      val msgTypes = streams.generatedMessageTypes()

      val expectedMsgTypes = Seq(Shutdown.replyType.messageType)

      assert(msgTypes == expectedMsgTypes)
    }

  }

}
