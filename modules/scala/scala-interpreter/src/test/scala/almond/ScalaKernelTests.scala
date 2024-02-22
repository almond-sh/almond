package almond

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{URL, URLClassLoader}
import java.nio.charset.StandardCharsets
import java.util.UUID

import almond.amm.AlmondCompilerLifecycleManager
import almond.channels.Channel
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.interpreter.TestInterpreter.StringBOps
import almond.kernel.{Kernel, KernelThreads}
import almond.protocol.{Execute => ProtocolExecute, _}
import almond.testkit.{ClientStreams, Dsl}
import almond.testkit.TestLogging.logCtx
import almond.TestUtil.{IOOps, KernelOps, execute => executeMessage, isScala212}
import almond.util.SequentialExecutionContext
import almond.util.ThreadUtil.{attemptShutdownExecutionContext, singleThreadedExecutionContext}
import ammonite.util.Colors
import cats.effect.IO
import fs2.Stream
import utest._

import scala.collection.compat._
import scala.jdk.CollectionConverters._

object ScalaKernelTests extends TestSuite {

  import almond.interpreter.TestInterpreter.StringBOps

  val interpreterEc = singleThreadedExecutionContext("test-interpreter")
  val bgVarEc       = new SequentialExecutionContext

  val threads = KernelThreads.create("test")

  implicit def runner: Dsl.Runner = TestUtil.kernelRunner(threads, interpreterEc)

  val maybePostImportNewLine = if (TestUtil.isScala2) "" else System.lineSeparator()

  val sp = " "
  val ls = System.lineSeparator()

  val scalaVersion = ammonite.compiler.CompilerBuilder.scalaVersion

  override def utestAfterAll() = {
    threads.attemptShutdown()
    if (!attemptShutdownExecutionContext(interpreterEc))
      println(s"Don't know how to shutdown $interpreterEc")
  }

  def withConsoleRedirect(f: (=> String, => String) => Unit): Unit = {
    val consoleOut = new ByteArrayOutputStream()
    val consoleErr = new ByteArrayOutputStream()
    val outStream  = new PrintStream(consoleOut, true)
    val errStream  = new PrintStream(consoleErr, true)
    val oldOut     = System.out
    val oldErr     = System.err
    try {
      System.setOut(outStream)
      System.setErr(errStream)

      f(
        consoleOut.toString(StandardCharsets.UTF_8.name()),
        consoleErr.toString(StandardCharsets.UTF_8.name())
      )
    }
    finally {
      System.setOut(oldOut)
      System.setErr(oldErr)
    }
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

      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

      // Initial messages from client

      val input = Stream(
        executeMessage("val n = scala.io.StdIn.readInt()"),
        executeMessage("val m = new java.util.Scanner(System.in).nextInt()"),
        executeMessage("""val s = "exit"""")
      )

      val streams =
        ClientStreams.create(input, stopWhen, inputHandler.orElse(ignoreExpectedReplies))

      kernel.run(streams.source, streams.sink, Nil)
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

      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

      val lastMsgId = UUID.randomUUID().toString

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(
            m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
          )

      val input = Stream(
        executeMessage("""sys.error("foo")"""),
        executeMessage("val n = 2"),
        executeMessage("""val s = "other"""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      kernel.run(streams.source, streams.sink, Nil)
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

      def checkError(ename: String, evalue: String, traceback: List[String]) = {
        assert(ename == "java.lang.RuntimeException")
        assert(evalue == "foo")
        assert(traceback.exists(_.contains("java.lang.RuntimeException: foo")))
        assert(traceback.exists(_.contains("ammonite.")))
      }

      val executeResultErrors = streams.executeResultErrors
      assert(executeResultErrors.size == 1)
      checkError(
        executeResultErrors.head.ename,
        executeResultErrors.head.evalue,
        executeResultErrors.head.traceback
      )

      val executeErrors = streams.executeErrors
      checkError(executeErrors(1)._1, executeErrors(1)._2, executeErrors(1)._3)

      val replies = streams.executeReplies

      // first code is in error, subsequent ones are cancelled because of the stop-on-error, so no results here
      val expectedReplies = Map()

      assert(replies == expectedReplies)
    }

    test("jvm-repr") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.jvmRepr()
    }

    test("updatable display") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.updatableDisplay()
    }

    test("auto-update Future results upon completion") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      ammonite.compiler.CompilerBuilder.scalaVersion
      almond.integration.Tests.autoUpdateFutureUponCompletion(scalaVersion)
    }

    test("auto-update Future results in background upon completion") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.autoUpdateFutureInBackgroundUponCompletion(scalaVersion)
    }

    test("auto-update Rx stuff upon change") {
      if (isScala212) {
        implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
        almond.integration.Tests.autoUpdateRxStuffUponChange()
        ""
      }
      else
        "Disabled"
    }

    test("handle interrupt messages") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.handleInterruptMessages()
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

      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

      val tpeName =
        if (scalaVersion.startsWith("2.")) "java.net.URL"
        else "URL"
      kernel.execute(
        """val url = Thread.currentThread().getContextClassLoader.getResource("foo")""",
        s"url: $tpeName = https://google.fr"
      )

      kernel.execute(
        """assert(url.toString == "https://google.fr")""",
        ""
      )
    }

    test("exit") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.exit()
    }

    test("trap output") {

      implicit val runner: Dsl.Runner = TestUtil.kernelRunner(
        threads,
        interpreterEc,
        processParams = _.copy(trapOutput = true)
      )
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

      almond.integration.Tests.trapOutput()
    }

    test("quiet=false") {
      withConsoleRedirect { (stdout, stderr) =>
        implicit val runner: Dsl.Runner = TestUtil.kernelRunner(
          threads,
          interpreterEc,
          processParams = _.copy(quiet = false)
        )
        implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

        almond.integration.Tests.quietOutput(stdout, stderr, quiet = false)
      }
    }

    test("quiet=true") {
      withConsoleRedirect { (stdout, stderr) =>
        implicit val runner: Dsl.Runner = TestUtil.kernelRunner(
          threads,
          interpreterEc,
          processParams = _.copy(quiet = true)
        )
        implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

        almond.integration.Tests.quietOutput(stdout, stderr, quiet = true)
      }
    }

    test("last exception") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.lastException()
    }

    test("history") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.history()
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

        implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

        val lastMsgId = UUID.randomUUID().toString
        val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
          (_, m) =>
            IO.pure(
              m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
            )

        // Initial messages from client

        val input = Stream(
          executeMessage("""var n = 2"""),
          executeMessage("""n = n + 1"""),
          executeMessage("""n += 2""", lastMsgId)
        )

        val streams = ClientStreams.create(input, stopWhen)

        kernel.run(streams.source, streams.sink, Nil)
          .unsafeRunTimedOrThrow()

        val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
        val publishMessageTypes  = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

        val expectedRequestsMessageTypes = Seq(
          "execute_reply",
          "execute_reply",
          "execute_reply"
        )

        val expectedPublishMessageTypes = Seq(
          Set(
            "execute_input",
            "display_data"
          ),
          Set(
            "execute_input",
            "update_display_data"
          ),
          Set(
            "execute_input",
            "update_display_data"
          )
        )

        assert(requestsMessageTypes == expectedRequestsMessageTypes)
        assert(TestUtil.comparePublishMessageTypes(
          expectedPublishMessageTypes,
          publishMessageTypes
        ))

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

      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

      val lastMsgId = UUID.randomUUID().toString
      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(
            m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
          )

      // Initial messages from client

      val input = Stream(
        executeMessage("""lazy val n = 2"""),
        executeMessage("""val a = { n; () }"""),
        executeMessage("""val b = { n; () }""", lastMsgId)
      )

      val streams = ClientStreams.create(input, stopWhen)

      kernel.run(streams.source, streams.sink, Nil)
        .unsafeRunTimedOrThrow()

      val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
      val publishMessageTypes  = streams.generatedMessageTypes(Set(Channel.Publish)).toVector

      val expectedRequestsMessageTypes = Seq(
        "execute_reply",
        "execute_reply",
        "execute_reply"
      )

      val expectedPublishMessageTypes = Seq(
        Set(
          "execute_input",
          "display_data"
        ),
        Set(
          "execute_input",
          "update_display_data"
        ),
        Set(
          "execute_input"
        )
      )

      assert(requestsMessageTypes == expectedRequestsMessageTypes)
      assert(TestUtil.comparePublishMessageTypes(expectedPublishMessageTypes, publishMessageTypes))

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

      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

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

      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

      val sbv =
        if (scalaVersion.startsWith("2.")) scalaVersion.split('.').take(2).mkString(".")
        else "2.13" // dotty compat

      kernel.execute(
        "import org.scalacheck.ScalacheckShapeless",
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        ),
        ignoreStreams = true
      )
      kernel.execute(
        "import org.scalacheck.Arbitrary",
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        ),
        ignoreStreams = true
      )
      val suffix = if (transitive) " --transitive" else ""
      kernel.execute(
        s"%AddDeps     com.github.alexarchambault scalacheck-shapeless_1.16_$sbv 1.3.1" + suffix,
        if (TestUtil.isScala2)
          "import $ivy.$"
        else
          "import $ivy.$                                                                " + maybePostImportNewLine,
        ignoreStreams = true // ignoring coursier messages (that it prints when downloading things)
      )
      kernel.execute(
        "import org.scalacheck.ScalacheckShapeless",
        "import org.scalacheck.ScalacheckShapeless" + maybePostImportNewLine
      )
      if (transitive)
        kernel.execute(
          "import org.scalacheck.Arbitrary",
          "import org.scalacheck.Arbitrary" + maybePostImportNewLine
        )
      else
        kernel.execute(
          "import org.scalacheck.Arbitrary",
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          ),
          ignoreStreams = true
        )
    }

    test("toree AddJar file") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.toreeAddJarFile(scalaVersion, sameCell = false)
    }

    test("toree AddJar file same cell") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.toreeAddJarFile(scalaVersion, sameCell = true)
    }

    test("toree AddJar URL") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.toreeAddJarURL(scalaVersion, sameCell = false)
    }

    test("toree AddJar URL same cell") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.toreeAddJarURL(scalaVersion, sameCell = true)
    }

    test("toree Html") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.toreeHtml()
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

      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()

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
        "res1: Vector[Int] = " + (1 to 200).toVector.toString
      )
      kernel.execute(
        "%truncation on",
        "",
        stdout =
          "Output WILL be truncated." + nl
      )
      kernel.execute(
        "(1 to 200).toVector",
        "res2: Vector[Int] = " +
          (1 to 38)
            .toVector
            .map("  " + _ + "," + "\n")
            .mkString("Vector(" + "\n", "", "...")
      )
    }

    test("toree custom cell magic") {
      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      almond.integration.Tests.toreeCustomCellMagic()
    }
  }

}
