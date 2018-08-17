package almond

import java.net.{URL, URLClassLoader}
import java.util.UUID

import almond.channels.Channel
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.protocol._
import almond.kernel.{ClientStreams, Kernel, KernelThreads}
import almond.util.ThreadUtil.{attemptShutdownExecutionContext, singleThreadedExecutionContext}
import ammonite.util.Colors
import argonaut.Json
import cats.effect.IO
import fs2.Stream
import utest._

import scala.concurrent.duration.{Duration, DurationInt}

object ScalaKernelTests extends TestSuite {

  private implicit class IOOps[T](private val io: IO[T]) extends AnyVal {
    // beware this is not *exactly* a timeout, more a max idle time say… (see the scaladoc of IO.unsafeRunTimed)
    def unsafeRunTimedOrThrow(duration: Duration): T =
      io.unsafeRunTimed(duration).getOrElse {
        throw new Exception("Timeout")
      }
  }

  // uncomment this to facilitate debugging
  // almond.util.OptionalLogger.enable()

  val interpreterEc = singleThreadedExecutionContext("test-interpreter")
  val bgVarEc = singleThreadedExecutionContext("test-bg-var")

  val threads = KernelThreads.create("test")

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }

  private def execute(sessionId: String, code: String, msgId: String = UUID.randomUUID().toString) =
    Message(
      Header(
        msgId,
        "test",
        sessionId,
        Execute.requestType.messageType,
        Some(Protocol.versionStr)
      ),
      Execute.Request(code, stop_on_error = Some(true))
    ).on(Channel.Requests)


  val tests = Tests {

    "stdin" - {

      // How the pseudo-client behaves

      val inputHandler = MessageHandler(Channel.Input, Input.requestType) { msg =>

        val resp = Input.Reply("32")

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

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.content.toString().contains("exit"))

      val sessionId = UUID.randomUUID().toString

      // Initial messages from client

      val input = Stream(
        execute(sessionId, "val n = scala.io.StdIn.readInt()"),
        execute(sessionId, "val m = new java.util.Scanner(System.in).nextInt()"),
        execute(sessionId, """val s = "exit"""")
      )


      val streams = ClientStreams.create(input, inputHandler.orElse(ignoreExpectedReplies), stopWhen)

      val interpreter = new ScalaInterpreter(
        initialColors = Colors.BlackWhite
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow(25.seconds)

      val replies = streams.executeReplies

      val expectedReplies = Map(
        1 -> "n: Int = 32",
        2 -> "m: Int = 32",
        3 -> """s: String = "exit""""
      )

      assert(replies == expectedReplies)
    }

    "stop on error" - {

      // How the pseudo-client behaves

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _) =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply" =>
      }

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """sys.error("foo")"""),
        execute(sessionId, "val n = 2"),
        execute(sessionId, """val s = "other"""", lastMsgId)
      )


      val streams = ClientStreams.create(input, ignoreExpectedReplies, stopWhen)

      val interpreter = new ScalaInterpreter(
        initialColors = Colors.BlackWhite
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow(15.seconds)

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "error",
        "execute_reply",
        "execute_input",
        "execute_reply",
        "execute_input",
        "execute_reply"
      )

      assert(messageTypes == expectedMessageTypes)

      val replies = streams.executeReplies

      // first code is in error, subsequent ones are cancelled because of the stop-on-error, so no results here
      val expectedReplies = Map()

      assert(replies == expectedReplies)
    }

    "updatable display" - {

      // How the pseudo-client behaves

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _) =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply" =>
      }

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """val handle = html("<b>foo</b>")"""),
        execute(sessionId, """handle.update("<i>bzz</i>")"""),
        execute(sessionId, """val s = "other"""", lastMsgId)
      )


      val streams = ClientStreams.create(input, ignoreExpectedReplies, stopWhen)

      val interpreter = new ScalaInterpreter(
        initialColors = Colors.BlackWhite
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow(15.seconds)

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "display_data",
        "execute_result",
        "execute_reply",
        "execute_input",
        "update_display_data",
        "execute_reply",
        "execute_input",
        "execute_result",
        "execute_reply"
      )

      assert(messageTypes == expectedMessageTypes)

      val displayData = streams.displayData
      val id = {
        val ids = displayData.flatMap(_._1.transient.display_id).toSet
        assert(ids.size == 1)
        ids.head
      }

      val expectedDisplayData = Seq(
        Execute.DisplayData(
          Map("text/html" -> Json.jString("<b>foo</b>")),
          Map(),
          Execute.DisplayData.Transient(Some(id))
        ) -> false,
        Execute.DisplayData(
          Map("text/html" -> Json.jString("<i>bzz</i>")),
          Map(),
          Execute.DisplayData.Transient(Some(id))
        ) -> true
      )

      assert(displayData == expectedDisplayData)
    }

    "auto-update Future results upon completion" - {

      // How the pseudo-client behaves

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _) =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply" =>
      }

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global"),
        execute(sessionId, "val f = Future { Thread.sleep(3000L); 2 }"),
        execute(sessionId, "Thread.sleep(6000L)", lastMsgId)
      )


      val streams = ClientStreams.create(input, ignoreExpectedReplies, stopWhen)

      val interpreter = new ScalaInterpreter(
        updateBackgroundVariablesEcOpt = Some(bgVarEc),
        initialColors = Colors.BlackWhite
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow(20.seconds)

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        // that one originates from the second line, but arrives while the third one is running
        "update_display_data",
        "execute_reply"
      )

      assert(messageTypes == expectedMessageTypes)

    }

    "auto-update Future results in background upon completion" - {

      // same as above, except no cell is running when the future completes

      // How the pseudo-client behaves

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _) =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply" =>
      }

      val sessionId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "update_display_data")

      // Initial messages from client

      val input = Stream(
        execute(sessionId, "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global"),
        execute(sessionId, "val f = Future { Thread.sleep(3000L); 2 }")
      )


      val streams = ClientStreams.create(input, ignoreExpectedReplies, stopWhen)

      val interpreter = new ScalaInterpreter(
        updateBackgroundVariablesEcOpt = Some(bgVarEc),
        initialColors = Colors.BlackWhite
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow(20.seconds)

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        "execute_result",
        "execute_reply",
        "update_display_data" // arrives while no cell is running
      )

      assert(messageTypes == expectedMessageTypes)
    }

    "handle interrupt messages" - {

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // How the pseudo-client behaves

      val interruptOnInput = MessageHandler(Channel.Input, Input.requestType) { msg =>
        Message(
          Header(
            UUID.randomUUID().toString,
            "test",
            sessionId,
            Interrupt.requestType.messageType,
            Some(Protocol.versionStr)
          ),
          Interrupt.Request
        ).streamOn(Channel.Control)
      }

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _) =>
        case (Channel.Requests, m) if m.header.msg_type == Execute.replyType.messageType =>
        case (Channel.Control, m) if m.header.msg_type == Interrupt.replyType.messageType =>
      }

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == Execute.replyType.messageType && m.parent_header.exists(_.msg_id == lastMsgId))


      // Initial messages from client

      val input = Stream(
        execute(sessionId, "val n = scala.io.StdIn.readInt()"),
        execute(sessionId, """val s = "ok done"""", msgId = lastMsgId)
      )


      val streams = ClientStreams.create(input, interruptOnInput.orElse(ignoreExpectedReplies), stopWhen)

      val interpreter = new ScalaInterpreter(
        initialColors = Colors.BlackWhite
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow(30.seconds)

      val messageTypes = streams.generatedMessageTypes()
      val controlMessageTypes = streams.generatedMessageTypes(Set(Channel.Control))

      val expectedMessageTypes = Seq(
        "execute_input",
        "stream",
        "error",
        "execute_reply",
        "execute_input",
        "execute_reply"
      )

      val expectedControlMessageTypes = Seq(
        "interrupt_reply"
      )

      assert(messageTypes == expectedMessageTypes)
      assert(controlMessageTypes == expectedControlMessageTypes)
    }

    "start from custom class loader" - {

      // How the pseudo-client behaves

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _) =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply" =>
      }

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """val url = Thread.currentThread().getContextClassLoader.getResource("foo")"""),
        execute(sessionId, """assert(url.toString == "https://google.fr")""", lastMsgId)
      )


      val streams = ClientStreams.create(input, ignoreExpectedReplies, stopWhen)

      val loader = new URLClassLoader(Array(), Thread.currentThread().getContextClassLoader) {
        override def getResource(name: String) =
          if (name == "foo")
            new URL("https://google.fr")
          else
            super.getResource(name)
      }

      val interpreter = new ScalaInterpreter(
        initialColors = Colors.BlackWhite,
        initialClassLoader = loader
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow(25.seconds)

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        "execute_reply"
      )

      assert(messageTypes == expectedMessageTypes)
    }

  }

}
