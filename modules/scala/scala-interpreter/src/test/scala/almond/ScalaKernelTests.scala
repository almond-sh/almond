package almond

import java.net.{URL, URLClassLoader}
import java.util.UUID

import almond.amm.AlmondCompilerLifecycleManager
import almond.channels.Channel
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.interpreter.TestInterpreter.StringBOps
import almond.kernel.{ClientStreams, Kernel, KernelThreads}
import almond.protocol.{Execute => ProtocolExecute, _}
import almond.TestLogging.logCtx
import almond.TestUtil._
import almond.util.SequentialExecutionContext
import almond.util.ThreadUtil.{attemptShutdownExecutionContext, singleThreadedExecutionContext}
import ammonite.util.Colors
import cats.effect.IO
import fs2.Stream
import utest._

import scala.collection.compat._

object ScalaKernelTests extends TestSuite {

  import almond.interpreter.TestInterpreter.StringBOps

  val interpreterEc = singleThreadedExecutionContext("test-interpreter")
  val bgVarEc = new SequentialExecutionContext

  val threads = KernelThreads.create("test")

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }


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

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.content.toString().contains("exit"))

      val sessionId = UUID.randomUUID().toString

      // Initial messages from client

      val input = Stream(
        execute(sessionId, "val n = scala.io.StdIn.readInt()"),
        execute(sessionId, "val m = new java.util.Scanner(System.in).nextInt()"),
        execute(sessionId, """val s = "exit"""")
      )


      val streams = ClientStreams.create(input, stopWhen, inputHandler.orElse(ignoreExpectedReplies))

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

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

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """sys.error("foo")"""),
        execute(sessionId, "val n = 2"),
        execute(sessionId, """val s = "other"""", lastMsgId)
      )


      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

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

    "jvm-repr" - {

      // How the pseudo-client behaves

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """class Bar(val value: String)"""),
        execute(sessionId, """kernel.register[Bar](bar => Map("text/plain" -> s"Bar(${bar.value})"))"""),
        execute(sessionId, """val b = new Bar("other")""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        "execute_reply",
        "execute_input",
        "display_data",
        "execute_reply"
      )

      assert(messageTypes == expectedMessageTypes)

      val displayData = streams.displayData

      val expectedDisplayData = Seq(
        ProtocolExecute.DisplayData(
          Map("text/plain" -> RawJson("\"Bar(other)\"".bytes)),
          Map()
        ) -> false
      )

      assert(displayData == expectedDisplayData)
    }

    "updatable display" - {

      // How the pseudo-client behaves

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """val handle = Html("<b>foo</b>")"""),
        execute(sessionId, """handle.withContent("<i>bzz</i>").update()"""),
        execute(sessionId, """val s = "other"""", lastMsgId)
      )


      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
      val publishMessageTypes = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

      val expectedRequestsMessageTypes = Seq(
        "execute_reply",
        "execute_reply",
        "execute_reply"
      )

      val expectedPublishMessageTypes = Seq(
        "execute_input",
        "display_data",
        "execute_input",
        "update_display_data",
        "execute_input",
        "execute_result"
      )

      assert(requestsMessageTypes == expectedRequestsMessageTypes)
      assert(publishMessageTypes == expectedPublishMessageTypes)

      val displayData = streams.displayData
      val id = {
        val ids = displayData.flatMap(_._1.transient.display_id).toSet
        assert(ids.size == 1)
        ids.head
      }

      val expectedDisplayData = Seq(
        ProtocolExecute.DisplayData(
          Map("text/html" -> RawJson("\"<b>foo</b>\"".bytes)),
          Map(),
          ProtocolExecute.DisplayData.Transient(Some(id))
        ) -> false,
        ProtocolExecute.DisplayData(
          Map("text/html" -> RawJson("\"<i>bzz</i>\"".bytes)),
          Map(),
          ProtocolExecute.DisplayData.Transient(Some(id))
        ) -> true
      )

      assert(displayData == expectedDisplayData)
    }

    "auto-update Future results upon completion" - {

      // How the pseudo-client behaves

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global"),
        execute(sessionId, "val f = Future { Thread.sleep(3000L); 2 }"),
        execute(sessionId, "Thread.sleep(6000L)", lastMsgId)
      )


      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          updateBackgroundVariablesEcOpt = Some(bgVarEc),
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        "display_data",
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

      val sessionId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "update_display_data")

      // Initial messages from client

      val input = Stream(
        execute(sessionId, "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global"),
        execute(sessionId, "val f = Future { Thread.sleep(3000L); 2 }")
      )


      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          updateBackgroundVariablesEcOpt = Some(bgVarEc),
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        "display_data",
        "execute_reply",
        "update_display_data" // arrives while no cell is running
      )

      assert(messageTypes == expectedMessageTypes)
    }

    "auto-update Rx stuff upon change" - {

      if (isScala212) {
        // How the pseudo-client behaves

        val sessionId = UUID.randomUUID().toString
        val lastMsgId = UUID.randomUUID().toString

        // When the pseudo-client exits

        val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
          (_, m) =>
            IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

        // Initial messages from client

        val input = Stream(
          execute(sessionId, "almondrx.setup()"),
          execute(sessionId, "val a = rx.Var(1)"),
          execute(sessionId, "a() = 2"),
          execute(sessionId, "a() = 3", lastMsgId)
        )


        val streams = ClientStreams.create(input, stopWhen)

        val interpreter = new ScalaInterpreter(
          params = ScalaInterpreterParams(
            updateBackgroundVariablesEcOpt = Some(bgVarEc),
            initialColors = Colors.BlackWhite
          ),
          logCtx = logCtx
        )

        val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
          .flatMap(_.run(streams.source, streams.sink))

        t.unsafeRunTimedOrThrow()

        val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
        val publishMessageTypes = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

        val expectedRequestsMessageTypes = Seq(
          "execute_reply",
          "execute_reply",
          "execute_reply",
          "execute_reply"
        )

        val expectedPublishMessageTypes = Seq(
          "execute_input",
          "stream",
          "execute_input",
          "display_data",
          "execute_input",
          "update_display_data",
          "execute_input",
          "update_display_data"
        )

        assert(requestsMessageTypes == expectedRequestsMessageTypes)
        assert(publishMessageTypes == expectedPublishMessageTypes)

        val displayData = streams.displayData.map {
          case (d, b) =>
            val d0 = d.copy(
              data = d.data.view.filterKeys(_ == "text/plain").toMap
            )
            (d0, b)
        }
        val id = {
          val ids = displayData.flatMap(_._1.transient.display_id).toSet
          assert(ids.size == 1)
          ids.head
        }

        val expectedDisplayData = Seq(
          ProtocolExecute.DisplayData(
            Map("text/plain" -> RawJson("\"a: rx.Var[Int] = 1\"".bytes)),
            Map(),
            ProtocolExecute.DisplayData.Transient(Some(id))
          ) -> false,
          ProtocolExecute.DisplayData(
            Map("text/plain" -> RawJson("\"a: rx.Var[Int] = 2\"".bytes)),
            Map(),
            ProtocolExecute.DisplayData.Transient(Some(id))
          ) -> true,
          ProtocolExecute.DisplayData(
            Map("text/plain" -> RawJson("\"a: rx.Var[Int] = 3\"".bytes)),
            Map(),
            ProtocolExecute.DisplayData.Transient(Some(id))
          ) -> true
        )

        assert(displayData == expectedDisplayData)
      }
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
        case (Channel.Requests, m) if m.header.msg_type == ProtocolExecute.replyType.messageType =>
        case (Channel.Control, m) if m.header.msg_type == Interrupt.replyType.messageType =>
      }

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == ProtocolExecute.replyType.messageType && m.parent_header.exists(_.msg_id == lastMsgId))


      // Initial messages from client

      val input = Stream(
        execute(sessionId, "val n = scala.io.StdIn.readInt()"),
        execute(sessionId, """val s = "ok done"""", msgId = lastMsgId)
      )


      val streams = ClientStreams.create(input, stopWhen, interruptOnInput.orElse(ignoreExpectedReplies))

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

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

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """val url = Thread.currentThread().getContextClassLoader.getResource("foo")"""),
        execute(sessionId, """assert(url.toString == "https://google.fr")""", lastMsgId)
      )


      val streams = ClientStreams.create(input, stopWhen)

      val loader = new URLClassLoader(Array(), Thread.currentThread().getContextClassLoader) {
        override def getResource(name: String) =
          if (name == "foo")
            new URL("https://google.fr")
          else
            super.getResource(name)
      }

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite,
          initialClassLoader = loader
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

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

    "exit" - {

      val sessionId = UUID.randomUUID().toString

      // Initial messages from client

      val input = Stream(
        execute(sessionId, "val n = 2"),
        execute(sessionId, "exit")
      )

      val streams = ClientStreams.create(input)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        )
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val messageTypes = streams.generatedMessageTypes()

      val expectedMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        "execute_reply"
      )

      assert(messageTypes == expectedMessageTypes)

      val payloads = streams.executeReplyPayloads


      val expectedPayloads = Map(
        2 -> Seq(
          RawJson("""{"source":"ask_exit","keepkernel":false}""".bytes)
        )
      )

      assert(payloads == expectedPayloads)
    }

    "trap output" - {

      val sessionId = UUID.randomUUID().toString

      // Initial messages from client

      val input = Stream(
        execute(sessionId, "val n = 2"),
        execute(sessionId, """println("Hello")"""),
        execute(sessionId, """System.err.println("Bbbb")"""),
        execute(sessionId, "exit")
      )

      val streams = ClientStreams.create(input)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite,
          trapOutput = true
        )
      )

      val t = Kernel.create(interpreter, interpreterEc, threads)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val messageTypes = streams.generatedMessageTypes()

      // no stream messages in particular
      val expectedMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_reply",
        "execute_input",
        "execute_reply",
        "execute_input",
        "execute_reply",
        "execute_input",
        "execute_reply"
      )

      assert(messageTypes == expectedMessageTypes)
    }

    "last exception" - {

      // How the pseudo-client behaves

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """val nullBefore = repl.lastException == null"""),
        execute(sessionId, """sys.error("foo")""", stopOnError = false),
        execute(sessionId, """val nullAfter = repl.lastException == null""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
      val publishMessageTypes = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

      val expectedRequestsMessageTypes = Seq(
        "execute_reply",
        "execute_reply",
        "execute_reply"
      )

      val expectedPublishMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_input",
        "error",
        "execute_input",
        "execute_result"
      )

      assert(requestsMessageTypes == expectedRequestsMessageTypes)
      assert(publishMessageTypes == expectedPublishMessageTypes)

      val replies = streams.executeReplies
      val expectedReplies = Map(
        1 -> "nullBefore: Boolean = true",
        3 -> "nullAfter: Boolean = false"
      )
      assert(replies == expectedReplies)
    }

    "history" - {

      // How the pseudo-client behaves

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """val before = repl.history.toVector"""),
        execute(sessionId, """val a = 2"""),
        execute(sessionId, """val b = a + 1"""),
        execute(sessionId, """val after = repl.history.toVector.mkString(",").toString""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
      val publishMessageTypes = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

      val expectedRequestsMessageTypes = Seq(
        "execute_reply",
        "execute_reply",
        "execute_reply",
        "execute_reply"
      )

      val expectedPublishMessageTypes = Seq(
        "execute_input",
        "execute_result",
        "execute_input",
        "execute_result",
        "execute_input",
        "execute_result",
        "execute_input",
        "execute_result"
      )

      assert(requestsMessageTypes == expectedRequestsMessageTypes)
      assert(publishMessageTypes == expectedPublishMessageTypes)

      val replies = streams.executeReplies
      val expectedReplies = Map(
        1 -> """before: Vector[String] = Vector("val before = repl.history.toVector")""",
        2 -> """a: Int = 2""",
        3 -> """b: Int = 3""",
        4 -> """after: String = "val before = repl.history.toVector,val a = 2,val b = a + 1,val after = repl.history.toVector.mkString(\",\").toString""""
      )
      assert(replies == expectedReplies)
    }

    "update vars" - {
      if (AlmondCompilerLifecycleManager.isAtLeast_2_12_7 && TestUtil.isScala2) {

        // How the pseudo-client behaves

        val sessionId = UUID.randomUUID().toString
        val lastMsgId = UUID.randomUUID().toString

        // When the pseudo-client exits

        val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
          (_, m) =>
            IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

        // Initial messages from client

        val input = Stream(
          execute(sessionId, """var n = 2"""),
          execute(sessionId, """n = n + 1"""),
          execute(sessionId, """n += 2""", lastMsgId)
        )

        val streams = ClientStreams.create(input, stopWhen)

        val interpreter = new ScalaInterpreter(
          params = ScalaInterpreterParams(
            updateBackgroundVariablesEcOpt = Some(bgVarEc),
            initialColors = Colors.BlackWhite
          ),
          logCtx = logCtx
        )

        val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
          .flatMap(_.run(streams.source, streams.sink))

        t.unsafeRunTimedOrThrow()

        val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
        val publishMessageTypes = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

        val expectedRequestsMessageTypes = Seq(
          "execute_reply",
          "execute_reply",
          "execute_reply"
        )

        val expectedPublishMessageTypes = Seq(
          "execute_input",
          "display_data",
          "execute_input",
          "update_display_data",
          "execute_input",
          "update_display_data"
        )

        assert(requestsMessageTypes == expectedRequestsMessageTypes)
        assert(publishMessageTypes == expectedPublishMessageTypes)

        val displayData = streams.displayData.map {
          case (d, b) =>
            val d0 = d.copy(
              data = d.data.view.filterKeys(_ == "text/plain").toMap
            )
            (d0, b)
        }
        val id = {
          val ids = displayData.flatMap(_._1.transient.display_id).toSet
          assert(ids.size == 1)
          ids.head
        }

        val expectedDisplayData = List(
          ProtocolExecute.DisplayData(
            Map("text/plain" -> RawJson("\"n: Int = 2\"".bytes)),
            Map(),
            ProtocolExecute.DisplayData.Transient(Some(id))
          ) -> false,
          ProtocolExecute.DisplayData(
            Map("text/plain" -> RawJson("\"n: Int = 3\"".bytes)),
            Map(),
            ProtocolExecute.DisplayData.Transient(Some(id))
          ) -> true,
          ProtocolExecute.DisplayData(
            Map("text/plain" -> RawJson("\"n: Int = 5\"".bytes)),
            Map(),
            ProtocolExecute.DisplayData.Transient(Some(id))
          ) -> true
        )

        assert(displayData == expectedDisplayData)
      }
    }

    def updateLazyValsTest(): Unit = {

      // How the pseudo-client behaves

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      // Initial messages from client

      val input = Stream(
        execute(sessionId, """lazy val n = 2"""),
        execute(sessionId, """val a = { n; () }"""),
        execute(sessionId, """val b = { n; () }""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          updateBackgroundVariablesEcOpt = Some(bgVarEc),
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
      val publishMessageTypes = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

      val expectedRequestsMessageTypes = Seq(
        "execute_reply",
        "execute_reply",
        "execute_reply"
      )

      val expectedPublishMessageTypes = Seq(
        "execute_input",
        "display_data",
        "execute_input",
        "update_display_data",
        "execute_input"
      )

      assert(requestsMessageTypes == expectedRequestsMessageTypes)
      assert(publishMessageTypes == expectedPublishMessageTypes)

      val displayData = streams.displayData.map {
        case (d, b) =>
          val d0 = d.copy(
            data = d.data.view.filterKeys(_ == "text/plain").toMap
          )
          (d0, b)
      }
      val id = {
        val ids = displayData.flatMap(_._1.transient.display_id).toSet
        assert(ids.size == 1)
        ids.head
      }

      val expectedDisplayData = List(
        ProtocolExecute.DisplayData(
          Map("text/plain" -> RawJson("\"n: Int = [lazy]\"".bytes)),
          Map(),
          ProtocolExecute.DisplayData.Transient(Some(id))
        ) -> false,
        ProtocolExecute.DisplayData(
          Map("text/plain" -> RawJson("\"n: Int = 2\"".bytes)),
          Map(),
          ProtocolExecute.DisplayData.Transient(Some(id))
        ) -> true
      )

      assert(displayData == expectedDisplayData)
    }

    "update lazy vals" - {
      if (TestUtil.isScala2) updateLazyValsTest()
      else "disabled"
    }
  }

}
