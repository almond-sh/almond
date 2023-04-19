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
import scala.util.Properties

object ScalaKernelTests extends TestSuite {

  import almond.interpreter.TestInterpreter.StringBOps

  val interpreterEc = singleThreadedExecutionContext("test-interpreter")
  val bgVarEc       = new SequentialExecutionContext

  val threads = KernelThreads.create("test")

  val maybePostImportNewLine = if (TestUtil.isScala2) "" else System.lineSeparator()

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }

  val tests = Tests {

    test("stdin") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

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
        case (Channel.Publish, _)                                          =>
        case (Channel.Requests, m) if m.header.msg_type == "execute_reply" =>
      }

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.content.toString().contains("exit"))

      implicit val sessionId: SessionId = SessionId()

      // Initial messages from client

      val input = Stream(
        execute("val n = scala.io.StdIn.readInt()"),
        execute("val m = new java.util.Scanner(System.in).nextInt()"),
        execute("""val s = "exit"""")
      )

      val streams =
        ClientStreams.create(input, stopWhen, inputHandler.orElse(ignoreExpectedReplies))

      kernel.run(streams.source, streams.sink)
        .unsafeRunTimedOrThrow()

      val replies = streams.executeReplies

      val expectedReplies = Map(
        1 -> "n: Int = 32",
        2 -> "m: Int = 32",
        3 -> """s: String = "exit""""
      )

      assert(replies == expectedReplies)
    }

    test("stop on error") {

      // There's something non-deterministic in this test.
      // It requires the 3 cells to execute to be sent at once at the beginning.
      // The exception running the first cell must make the kernel discard (not compile
      // nor run) the other two, that are queued.
      // That means, the other 2 cells must have been queued when the first cell's thrown
      // exception is caught by the kernel.
      // Because of that, we can't rely on individual calls to 'kernel.execute' like
      // the other tests do, as these send messages one after the other, sending the
      // next one when the previous one is done running (so no messages would be queued.)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      val lastMsgId = UUID.randomUUID().toString

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(
            m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
          )

      val input = Stream(
        execute("""sys.error("foo")"""),
        execute("val n = 2"),
        execute("""val s = "other"""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      kernel.run(streams.source, streams.sink)
        .unsafeRunTimedOrThrow()

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

    test("jvm-repr") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      kernel.execute("""class Bar(val value: String)""", "defined class Bar")
      kernel.execute(
        """kernel.register[Bar](bar => Map("text/plain" -> s"Bar(${bar.value})"))""",
        ""
      )
      kernel.execute(
        """val b = new Bar("other")""",
        "",
        displaysText = Seq("Bar(other)")
      )
    }

    test("updatable display") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      kernel.execute(
        """val handle = Html("<b>foo</b>")""",
        "",
        displaysHtml = Seq("<b>foo</b>")
      )

      kernel.execute(
        """handle.withContent("<i>bzz</i>").update()""",
        "",
        displaysHtmlUpdates = Seq("<i>bzz</i>")
      )
    }

    test("auto-update Future results upon completion") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          updateBackgroundVariablesEcOpt = Some(bgVarEc),
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      val sp = " "
      val ls = System.lineSeparator()

      kernel.execute(
        "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global",
        // Multi-line with stripMargin seems to be a problem on our Windows CI for this test,
        // but not for the other ones using stripMargin…
        s"import scala.concurrent.Future;$sp$ls" +
          s"import scala.concurrent.ExecutionContext.Implicits.global$maybePostImportNewLine"
      )

      kernel.execute(
        "val f = Future { Thread.sleep(3000L); 2 }",
        "",
        displaysText = Seq("f: Future[Int] = [running]")
      )

      kernel.execute(
        "Thread.sleep(6000L)",
        "",
        // the update originates from the previous cell, but arrives while the third one is running
        displaysTextUpdates = Seq(
          if (TestUtil.isScala212) "f: Future[Int] = Success(2)"
          else "f: Future[Int] = Success(value = 2)"
        )
      )
    }

    test("auto-update Future results in background upon completion") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          updateBackgroundVariablesEcOpt = Some(bgVarEc),
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      // same as above, except no cell is running when the future completes

      // How the pseudo-client behaves

      implicit val sessionId: SessionId = SessionId()

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "update_display_data")

      // Initial messages from client

      val input = Stream(
        execute(
          "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global"
        ),
        execute("val f = Future { Thread.sleep(3000L); 2 }")
      )

      val streams = ClientStreams.create(input, stopWhen)

      kernel.run(streams.source, streams.sink)
        .unsafeRunTimedOrThrow()

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

    test("auto-update Rx stuff upon change") {

      if (isScala212) {

        val interpreter = new ScalaInterpreter(
          params = ScalaInterpreterParams(
            updateBackgroundVariablesEcOpt = Some(bgVarEc),
            initialColors = Colors.BlackWhite
          ),
          logCtx = logCtx
        )

        val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
          .unsafeRunTimedOrThrow()

        implicit val sessionId: SessionId = SessionId()

        // When the pseudo-client exits

        val lastMsgId = UUID.randomUUID().toString
        val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
          (_, m) =>
            IO.pure(
              m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
            )

        // Initial messages from client

        val input = Stream(
          execute("almondrx.setup()"),
          execute("val a = rx.Var(1)"),
          execute("a() = 2"),
          execute("a() = 3", lastMsgId)
        )

        val streams = ClientStreams.create(input, stopWhen)

        kernel.run(streams.source, streams.sink)
          .unsafeRunTimedOrThrow()

        val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
        val publishMessageTypes  = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

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

    test("handle interrupt messages") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      val interruptOnInput = MessageHandler(Channel.Input, Input.requestType) { msg =>
        Message(
          Header(
            UUID.randomUUID().toString,
            "test",
            sessionId.sessionId,
            Interrupt.requestType.messageType,
            Some(Protocol.versionStr)
          ),
          Interrupt.Request
        ).streamOn(Channel.Control)
      }

      val ignoreExpectedReplies = MessageHandler.discard {
        case (Channel.Publish, _)                                                                =>
        case (Channel.Requests, m) if m.header.msg_type == ProtocolExecute.replyType.messageType =>
        case (Channel.Control, m) if m.header.msg_type == Interrupt.replyType.messageType        =>
      }

      // When the pseudo-client exits

      val lastMsgId = UUID.randomUUID().toString
      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(
            m.header.msg_type == ProtocolExecute.replyType.messageType && m.parent_header.exists(
              _.msg_id == lastMsgId
            )
          )

      // Initial messages from client

      val input = Stream(
        execute("val n = scala.io.StdIn.readInt()"),
        execute("""val s = "ok done"""", msgId = lastMsgId)
      )

      val streams =
        ClientStreams.create(input, stopWhen, interruptOnInput.orElse(ignoreExpectedReplies))

      kernel.run(streams.source, streams.sink)
        .unsafeRunTimedOrThrow()

      val messageTypes        = streams.generatedMessageTypes()
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

    test("start from custom class loader") {

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

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()
      val lastMsgId                     = UUID.randomUUID().toString

      // When the pseudo-client exits

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(
            m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
          )

      // Initial messages from client

      val input = Stream(
        execute("""val url = Thread.currentThread().getContextClassLoader.getResource("foo")"""),
        execute("""assert(url.toString == "https://google.fr")""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      kernel.run(streams.source, streams.sink)
        .unsafeRunTimedOrThrow()

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

    test("exit") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      // Initial messages from client

      val input = Stream(
        execute("val n = 2"),
        execute("exit")
      )

      val streams = ClientStreams.create(input)

      kernel.run(streams.source, streams.sink)
        .unsafeRunTimedOrThrow()

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

    test("trap output") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite,
          trapOutput = true
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      // Initial messages from client

      val input = Stream(
        execute("val n = 2"),
        execute("""println("Hello")"""),
        execute("""System.err.println("Bbbb")"""),
        execute("exit")
      )

      val streams = ClientStreams.create(input)

      kernel.run(streams.source, streams.sink)
        .unsafeRunTimedOrThrow()

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

    test("last exception") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      kernel.execute(
        """val nullBefore = repl.lastException == null""",
        "nullBefore: Boolean = true"
      )
      kernel.execute("""sys.error("foo")""", expectError = true)
      kernel.execute("""val nullAfter = repl.lastException == null""", "nullAfter: Boolean = false")
    }

    test("history") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      kernel.execute(
        """val before = repl.history.toVector""",
        """before: Vector[String] = Vector("val before = repl.history.toVector")"""
      )
      kernel.execute("val a = 2", "a: Int = 2")
      kernel.execute("val b = a + 1", "b: Int = 3")
      kernel.execute(
        """val after = repl.history.toVector.mkString(",").toString""",
        """after: String = "val before = repl.history.toVector,val a = 2,val b = a + 1,val after = repl.history.toVector.mkString(\",\").toString""""
      )
    }

    test("update vars") {
      if (AlmondCompilerLifecycleManager.isAtLeast_2_12_7 && TestUtil.isScala2) {

        val interpreter = new ScalaInterpreter(
          params = ScalaInterpreterParams(
            updateBackgroundVariablesEcOpt = Some(bgVarEc),
            initialColors = Colors.BlackWhite
          ),
          logCtx = logCtx
        )

        val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
          .unsafeRunTimedOrThrow()

        implicit val sessionId: SessionId = SessionId()

        val lastMsgId = UUID.randomUUID().toString
        val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
          (_, m) =>
            IO.pure(
              m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
            )

        // Initial messages from client

        val input = Stream(
          execute("""var n = 2"""),
          execute("""n = n + 1"""),
          execute("""n += 2""", lastMsgId)
        )

        val streams = ClientStreams.create(input, stopWhen)

        kernel.run(streams.source, streams.sink)
          .unsafeRunTimedOrThrow()

        val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
        val publishMessageTypes  = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

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

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          updateBackgroundVariablesEcOpt = Some(bgVarEc),
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      val lastMsgId = UUID.randomUUID().toString
      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(
            m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
          )

      // Initial messages from client

      val input = Stream(
        execute("""lazy val n = 2"""),
        execute("""val a = { n; () }"""),
        execute("""val b = { n; () }""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      kernel.run(streams.source, streams.sink)
        .unsafeRunTimedOrThrow()

      val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
      val publishMessageTypes  = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

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

    test("update lazy vals") {
      if (TestUtil.isScala2) updateLazyValsTest()
      else "disabled"
    }

    test("hooks") {

      val predef =
        """private val foos0 = new scala.collection.mutable.ListBuffer[String]
          |
          |def foos(): List[String] =
          |  foos0.result()
          |
          |kernel.addExecuteHook { code =>
          |  import almond.api.JupyterApi
          |
          |  var errorOpt = Option.empty[JupyterApi.ExecuteHookResult]
          |  val remainingCode = code.linesWithSeparators.zip(code.linesIterator)
          |    .map {
          |      case (originalLine, line) =>
          |        if (line == "%AddFoo") ""
          |        else if (line.startsWith("%AddFoo ")) {
          |          foos0 ++= line.split("\\s+").drop(1).toSeq
          |          ""
          |        }
          |        else if (line == "%Error") {
          |          errorOpt = Some(JupyterApi.ExecuteHookResult.Error("thing", "erroring", List("aa", "bb")))
          |          ""
          |        }
          |        else if (line == "%Abort") {
          |          errorOpt = Some(JupyterApi.ExecuteHookResult.Abort)
          |          ""
          |        }
          |        else if (line == "%Exit") {
          |          errorOpt = Some(JupyterApi.ExecuteHookResult.Exit)
          |          ""
          |        }
          |        else
          |          originalLine
          |    }
          |    .mkString
          |
          |  errorOpt.toLeft(remainingCode)
          |}
          |""".stripMargin

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite,
          predefCode = predef
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      kernel.execute(
        "val before = foos()",
        "before: List[String] = List()"
      )
      kernel.execute("""%AddFoo a""", "")
      kernel.execute("""%AddFoo b""", "")
      kernel.execute(
        "val after = foos()",
        """after: List[String] = List("a", "b")"""
      )
      kernel.execute(
        "%Error",
        errors = Seq(
          ("thing", "erroring", List("thing: erroring", "    aa", "    bb"))
        )
      )
      kernel.execute("%Abort")
      kernel.execute(
        """val a = "a"
          |""".stripMargin,
        """a: String = "a""""
      )
      kernel.execute(
        """%Exit
          |val b = "b"
          |""".stripMargin,
        ""
      )
    }

    test("toree AddDeps") {
      toreeAddDepsTest()
    }
    // unsupported yet, needs tweaking in the Ammonite dependency parser
    // test("toree AddDeps intransitive") {
    //   toreeAddDepsTest(transitive = false)
    // }

    def toreeAddDepsTest(transitive: Boolean = true): Unit = {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite,
          toreeMagics = true
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      val sbv = {
        val sv = Properties.versionNumberString
        if (sv.startsWith("2.")) sv.split('.').take(2).mkString(".")
        else sv.takeWhile(_ != '.')
      }

      kernel.execute(
        "import caseapp.CaseApp",
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        ),
        ignoreStreams = true
      )
      kernel.execute(
        "import caseapp.util._",
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        ),
        ignoreStreams = true
      )
      val suffix = if (transitive) " --transitive" else ""
      kernel.execute(
        s"%AddDeps     com.github.alexarchambault case-app_$sbv 2.1.0-M24" + suffix,
        "import $ivy.$                                               " + (" " * sbv.length) + maybePostImportNewLine,
        ignoreStreams = true // ignoring coursier messages (that it prints when downloading things)
      )
      kernel.execute(
        "import caseapp.CaseApp",
        "import caseapp.CaseApp" + maybePostImportNewLine
      )
      if (transitive)
        kernel.execute(
          "import caseapp.util._",
          "import caseapp.util._" + maybePostImportNewLine
        )
      else
        kernel.execute(
          "import caseapp.util._",
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          ),
          ignoreStreams = true
        )
    }

    test("toree Html") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite,
          toreeMagics = true
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      kernel.execute(
        """%%html
          |<p>
          |<b>Hello</b>
          |</p>
          |""".stripMargin,
        "",
        displaysHtml = Seq(
          """<p>
            |<b>Hello</b>
            |</p>
            |""".stripMargin
        )
      )
    }

    test("toree Truncation") {

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite,
          toreeMagics = true
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      val nl = System.lineSeparator()

      kernel.execute(
        "%truncation",
        "",
        stdout =
          "Truncation is currently on" + nl
      )
      kernel.execute(
        "%truncation off",
        "",
        stdout =
          "Output will NOT be truncated" + nl
      )
      kernel.execute(
        "(1 to 200).toVector",
        "res0: Vector[Int] = " + (1 to 200).toVector.toString
      )
      kernel.execute(
        "%truncation on",
        "",
        stdout =
          "Output WILL be truncated." + nl
      )
      kernel.execute(
        "(1 to 200).toVector",
        "res1: Vector[Int] = " +
          (1 to 38)
            .toVector
            .map("  " + _ + "," + "\n")
            .mkString("Vector(" + "\n", "", "...")
      )
    }

    test("toree custom cell magic") {

      val predef =
        """almond.toree.CellMagicHook.addHandler("test") { (_, content) =>
          |  import almond.api.JupyterApi
          |  import almond.interpreter.api.DisplayData
          |
          |  Left(JupyterApi.ExecuteHookResult.Success(DisplayData.text(content)))
          |}
          |
          |almond.toree.CellMagicHook.addHandler("thing") { (_, content) =>
          |  import almond.api.JupyterApi
          |  import almond.interpreter.api.DisplayData
          |
          |  val nl = System.lineSeparator()
          |  Right(s"val thing = {" + nl + content + nl + "}" + nl)
          |}
          |""".stripMargin

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          initialColors = Colors.BlackWhite,
          predefCode = predef,
          toreeMagics = true
        ),
        logCtx = logCtx
      )

      val kernel = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()

      implicit val sessionId: SessionId = SessionId()

      kernel.execute(
        """%%test
          |foo
          |a
          |""".stripMargin,
        """foo
          |a
          |""".stripMargin
      )

      val nl = System.lineSeparator()

      kernel.execute(
        """%%thing
          |println("Hello")
          |2
          |""".stripMargin,
        "thing: Int = 2",
        stdout =
          "Hello" + nl +
            "thing: Int = 2"
      )
    }
  }

}
