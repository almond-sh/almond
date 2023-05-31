package almond.integration

import almond.channels.Channel
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.protocol.{Execute => ProtocolExecute, _}
import almond.testkit.Dsl._
import com.eed3si9n.expecty.Expecty.expect

import java.io.File
import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.Properties

object Tests {

  private val sp = " "
  private val ls = System.lineSeparator()

  private def maybePostImportNewLine(isScala2: Boolean) =
    if (isScala2) "" else System.lineSeparator()

  def jvmRepr()(implicit sessionId: SessionId, runner: Runner): Unit =
    runner.withSession() { implicit session =>
      execute("""class Bar(val value: String)""", "defined class Bar")
      execute(
        """kernel.register[Bar](bar => Map("text/plain" -> s"Bar(${bar.value})"))""",
        ""
      )
      execute(
        """val b = new Bar("other")""",
        "",
        displaysText = Seq("Bar(other)")
      )
    }

  def updatableDisplay()(implicit sessionId: SessionId, runner: Runner): Unit =
    runner.withSession() { implicit session =>
      execute(
        """val handle = Html("<b>foo</b>")""",
        "",
        displaysHtml = Seq("<b>foo</b>")
      )

      execute(
        """handle.withContent("<i>bzz</i>").update()""",
        "",
        displaysHtmlUpdates = Seq("<i>bzz</i>")
      )
    }

  def autoUpdateFutureUponCompletion(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit = {

    val isScala2   = scalaVersion.startsWith("2.")
    val isScala212 = scalaVersion.startsWith("2.12.")

    runner.withSession() { implicit session =>

      execute(
        "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global",
        // Multi-line with stripMargin seems to be a problem on our Windows CI for this test,
        // but not for the other ones using stripMargin…
        s"import scala.concurrent.Future;$sp$ls" +
          s"import scala.concurrent.ExecutionContext.Implicits.global${maybePostImportNewLine(isScala2)}"
      )

      execute(
        "val f = Future { Thread.sleep(3000L); 2 }",
        "",
        displaysText = Seq("f: Future[Int] = [running]")
      )

      execute(
        "Thread.sleep(6000L)",
        "",
        // the update originates from the previous cell, but arrives while the third one is running
        displaysTextUpdates = Seq(
          if (isScala212) "f: Future[Int] = Success(2)"
          else "f: Future[Int] = Success(value = 2)"
        )
      )
    }
  }

  def autoUpdateFutureInBackgroundUponCompletion(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit = {

    // same as above, except no cell is running when the future completes

    val isScala2   = scalaVersion.startsWith("2.")
    val isScala212 = scalaVersion.startsWith("2.12.")

    runner.withSession() { implicit session =>
      execute(
        "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global",
        // Multi-line with stripMargin seems to be a problem on our Windows CI for this test,
        // but not for the other ones using stripMargin…
        s"import scala.concurrent.Future;$sp$ls" +
          s"import scala.concurrent.ExecutionContext.Implicits.global${maybePostImportNewLine(isScala2)}"
      )

      execute(
        "val f = Future { Thread.sleep(3000L); 2 }",
        "",
        displaysText = Seq("f: Future[Int] = [running]"),
        displaysTextUpdates = Seq(
          if (isScala212) "f: Future[Int] = Success(2)"
          else "f: Future[Int] = Success(value = 2)"
        ),
        waitForUpdateDisplay = true
      )
    }
  }

  def autoUpdateRxStuffUponChange()(implicit sessionId: SessionId, runner: Runner): Unit =
    runner.withSession() { implicit session =>
      execute(
        "almondrx.setup()",
        "",
        ignoreStreams = true
      )

      execute(
        "val a = rx.Var(1)",
        "",
        displaysText = Seq(
          "a: rx.Var[Int] = 1"
        )
      )

      execute(
        "a() = 2",
        "",
        displaysTextUpdates = Seq(
          "a: rx.Var[Int] = 2"
        )
      )

      execute(
        "a() = 3",
        "",
        displaysTextUpdates = Seq(
          "a: rx.Var[Int] = 3"
        )
      )
    }

  def handleInterruptMessages()(implicit sessionId: SessionId, runner: Runner): Unit = {

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

    runner.withSession() { implicit session =>
      execute(
        "val n = scala.io.StdIn.readInt()",
        ignoreStreams = true,
        expectError = true,
        expectInterrupt = true,
        handler = interruptOnInput.orElse(ignoreExpectedReplies)
      )

      execute(
        """val s = "ok done"""",
        """s: String = "ok done""""
      )
    }
  }

  def exit()(implicit sessionId: SessionId, runner: Runner): Unit =
    runner.withSession() { implicit session =>
      execute(
        "val n = 2",
        "n: Int = 2"
      )

      execute(
        "exit",
        "",
        replyPayloads = Seq(
          """{"source":"ask_exit","keepkernel":false}"""
        )
      )
    }

  def trapOutput()(implicit sessionId: SessionId, runner: Runner): Unit =
    runner.withSession() { implicit session =>
      execute(
        "val n = 2",
        "n: Int = 2"
      )

      execute(
        """println("Hello")""",
        "",
        stdout = "",
        stderr = ""
      )

      execute(
        """System.err.println("Bbbb")""",
        "",
        stdout = "",
        stderr = ""
      )

      execute(
        "exit",
        "",
        stdout = "",
        stderr = ""
      )
    }

  def lastException()(implicit sessionId: SessionId, runner: Runner): Unit =
    runner.withSession() { implicit session =>
      execute(
        """val nullBefore = repl.lastException == null""",
        "nullBefore: Boolean = true"
      )
      execute("""sys.error("foo")""", expectError = true)
      execute("""val nullAfter = repl.lastException == null""", "nullAfter: Boolean = false")
    }

  def history()(implicit sessionId: SessionId, runner: Runner): Unit =
    runner.withSession() { implicit session =>
      execute(
        """val before = repl.history.toVector""",
        """before: Vector[String] = Vector("val before = repl.history.toVector")"""
      )
      execute("val a = 2", "a: Int = 2")
      execute("val b = a + 1", "b: Int = 3")
      execute(
        """val after = repl.history.toVector.mkString(",").toString""",
        """after: String = "val before = repl.history.toVector,val a = 2,val b = a + 1,val after = repl.history.toVector.mkString(\",\").toString""""
      )
    }

  def toreeAddJarFile(scalaVersion: String, sameCell: Boolean)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit =
    runner.withSession("--toree-magics") { implicit session =>

      val jar = coursierapi.Fetch.create()
        .addDependencies(coursierapi.Dependency.of("info.picocli", "picocli", "4.7.3"))
        .fetch()
        .asScala
        .head
      val jarUri = jar.toURI

      execute(
        "import picocli.CommandLine",
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        ),
        ignoreStreams = true
      )

      if (sameCell)
        execute(
          s"%AddJar $jarUri" + ls +
            "import picocli.CommandLine" + ls,
          "import $cp.$" + ls + ls +
            "import picocli.CommandLine" + ls,
          ignoreStreams = true,
          trimReplyLines = true
        )
      else {
        execute(
          s"%AddJar $jarUri",
          "import $cp.$" + maybePostImportNewLine(scalaVersion.startsWith("2.")),
          ignoreStreams = true,
          trimReplyLines = true
        )

        execute(
          "import picocli.CommandLine",
          "import picocli.CommandLine" + maybePostImportNewLine(scalaVersion.startsWith("2."))
        )
      }
    }

  def toreeAddJarURL(scalaVersion: String, sameCell: Boolean)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit =
    runner.withSession("--toree-magics") { implicit session =>

      val jar = coursierapi.Fetch.create()
        .addDependencies(coursierapi.Dependency.of("info.picocli", "picocli", "4.7.3"))
        .fetchResult()
        .getArtifacts
        .asScala
        .head
        .getKey
        .getUrl

      execute(
        "import picocli.CommandLine",
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        ),
        ignoreStreams = true
      )

      if (sameCell)
        execute(
          s"%AddJar $jar" + ls +
            "import picocli.CommandLine" + ls,
          "import $cp.$" + ls + ls +
            "import picocli.CommandLine" + ls,
          ignoreStreams = true,
          trimReplyLines = true
        )
      else {
        execute(
          s"%AddJar $jar",
          "import $cp.$" + maybePostImportNewLine(scalaVersion.startsWith("2.")),
          ignoreStreams = true,
          trimReplyLines = true
        )

        execute(
          "import picocli.CommandLine",
          "import picocli.CommandLine" + maybePostImportNewLine(scalaVersion.startsWith("2."))
        )
      }
    }

  private def java17Cmd(): String = {
    val isAtLeastJava17 =
      scala.util.Try(sys.props("java.version").takeWhile(_.isDigit).toInt).toOption.exists(_ >= 17)
    val javaHome =
      if (isAtLeastJava17) new File(sys.props("java.home"))
      else coursierapi.JvmManager.create().get("17")
    val ext = if (Properties.isWin) ".exe" else ""
    new File(javaHome, "bin/java" + ext).toString
  }

  private def scalaCliLauncher(): File =
    coursierapi.Cache.create()
      .get(coursierapi.Artifact.of(
        "https://github.com/VirtusLab/scala-cli/releases/download/v1.0.0-RC1/scala-cli"
      ))

  def toreeAddJarCustomProtocol(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit = {

    val picocliJar = coursierapi.Fetch.create()
      .addDependencies(coursierapi.Dependency.of("info.picocli", "picocli", "4.7.3"))
      .fetch()
      .asScala
      .head

    val pkg               = "almond.test.custom"
    val tmpDir            = os.temp.dir(prefix = "almond.add-jar-test")
    val escapedPicocliJar = picocliJar.toString.replace("\\", "\\\\")
    val code =
      s"""//> using scala "$scalaVersion"
         |package $pkg.foo
         |
         |class Handler extends java.net.URLStreamHandler {
         |  override def openConnection(url: java.net.URL): java.net.URLConnection =
         |    new java.net.URLConnection(url) {
         |      override def connect(): Unit = ()
         |      override def getInputStream(): java.io.InputStream =
         |        new java.io.FileInputStream(new java.io.File("$escapedPicocliJar"))
         |    }
         |}
         |""".stripMargin
    os.write(tmpDir / "FooURLConnection.scala", code)

    val extraCp = os.proc(
      java17Cmd(),
      "-jar",
      scalaCliLauncher().toString,
      "--power",
      "compile",
      "--print-class-path",
      "."
    )
      .call(cwd = tmpDir, stdin = os.Inherit)
      .out.trim()

    val predef =
      s"""
         |private def registerPackage(): Unit = {
         |  val currentOpt = sys.props.get("java.protocol.handler.pkgs")
         |  val updatedValue = currentOpt.fold("")(_ + "|") + "$pkg"
         |  sys.props("java.protocol.handler.pkgs") = updatedValue
         |}
         |
         |private def resetHandlers(): Unit =
         |  try java.net.URL.setURLStreamHandlerFactory(null)
         |  catch {
         |    case e: Error => throw e// Ignore
         |  }
         |
         |registerPackage()
         |resetHandlers()
         |""".stripMargin

    val predefPath = tmpDir / "predef.sc"
    os.write(predefPath, predef)

    runner.withExtraClassPathSession(extraCp)("--toree-magics", "--predef", predefPath.toString) {
      implicit session =>
        execute(
          "import picocli.CommandLine",
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          ),
          ignoreStreams = true
        )

        execute(
          "%AddJar foo://thing/a/b" + ls,
          "import $cp.$" + ls,
          trimReplyLines = true
        )

        execute(
          "import picocli.CommandLine",
          "import picocli.CommandLine" + maybePostImportNewLine(scalaVersion.startsWith("2."))
        )
    }
  }

  def toreeCustomCellMagic()(implicit sessionId: SessionId, runner: Runner): Unit = {

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

    val tmpDir     = os.temp.dir(prefix = "almond.custom-cell-magic-test")
    val predefPath = tmpDir / "predef.sc"
    os.write(predefPath, predef)

    runner.withLauncherOptionsSession("--shared", "sh.almond::toree-hooks")(
      "--toree-magics",
      "--predef",
      predefPath.toString
    ) { implicit session =>
      execute(
        """%%test
          |foo
          |a
          |""".stripMargin,
        """foo
          |a
          |""".stripMargin
      )

      execute(
        "%LsMagic",
        "",
        stdout =
          "Available line magics:" + ls +
            "%adddeps %addjar %lsmagic %truncation" + ls +
            ls +
            "Available cell magics:" + ls +
            "%%html %%javascript %%test %%thing" + ls +
            ls
      )

      execute(
        """%%thing
          |println("Hello")
          |2
          |""".stripMargin,
        "thing: Int = 2",
        stdout =
          "Hello" + ls +
            "thing: Int = 2"
      )
    }
  }

  def compileOnly()(implicit sessionId: SessionId, runner: Runner): Unit =
    runner.withSession("--compile-only", "--toree-magics") { implicit session =>
      execute(
        """println("Hello from compile-only kernel")""",
        "",
        stdout = "",
        stderr = ""
      )

      execute(
        """System.err.println("Hello from compile-only kernel")""",
        "",
        stdout = "",
        stderr = ""
      )

      execute(
        "import picocli.CommandLine",
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        ),
        ignoreStreams = true
      )

      execute(
        """%AddDeps info.picocli picocli 4.7.3 --transitive
          |""".stripMargin,
        "",
        ignoreStreams = true
      )

      execute(
        "new ListBuffer[String]",
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        ),
        ignoreStreams = true
      )

      execute(
        "import picocli.CommandLine; import scala.collection.mutable.ListBuffer",
        "",
        stdout = "",
        stderr = ""
      )

      execute(
        "new ListBuffer[String]",
        "",
        stdout = "",
        stderr = ""
      )

      execute(
        "sys.exit(1)",
        "",
        stdout = "",
        stderr = ""
      )
    }

  def extraCp(scalaVersion: String)(implicit sessionId: SessionId, runner: Runner): Unit = {

    val sbv = scalaVersion.split('.').take(2).mkString(".")

    val kernelShapelessVersion = "2.3.10" // might need to be updated when bumping case-app
    val testShapelessVersion   = "2.3.3"  // no need to bump that one

    assert(kernelShapelessVersion != testShapelessVersion)

    val shapelessJar = coursierapi.Fetch.create()
      .addDependencies(
        coursierapi.Dependency.of("com.chuusai", "shapeless_" + sbv, testShapelessVersion)
          .withTransitive(false)
      )
      .fetch()
      .asScala
      .toList
    assert(shapelessJar.length == 1)

    runner.withSession("--extra-class-path", shapelessJar.mkString(File.pathSeparator)) {
      implicit session =>
        execute(
          "import shapeless._" + ls +
            """val l = 1 :: "aa" :: true :: HNil""",
          "import shapeless._" + ls + ls +
            """l: Int :: String :: Boolean :: HNil = 1 :: "aa" :: true :: HNil"""
        )

        execute(
          s"""val check = HNil.getClass.getProtectionDomain.getCodeSource.getLocation.toExternalForm.endsWith("-$testShapelessVersion.jar")""",
          "check: Boolean = true"
        )

        execute(
          s"""val kernelCheck = kernel.kernelClassLoader.loadClass(HNil.getClass.getName).getProtectionDomain.getCodeSource.getLocation.toExternalForm.stripSuffix("/").stripSuffix("!").endsWith("-$kernelShapelessVersion.jar")""",
          "kernelCheck: Boolean = true"
        )
    }
  }

  def inspections(scalaVersion: String)(implicit sessionId: SessionId, runner: Runner): Unit = {

    def sbv = scalaVersion.split('.').take(2).mkString(".")

    val isJava8 = sys.props.get("java.version")
      .exists(v => v == "1.8" || v.startsWith("1.8."))
    val extraJars =
      if (isJava8)
        // Adding these to workaround issues indexing the kernel launcher in Java 8
        coursierapi.Fetch.create()
          .addDependencies(coursierapi.Dependency.of("com.lihaoyi", "os-lib_" + sbv, "0.9.0"))
          .addClassifiers("_", "sources")
          .fetch()
          .asScala
          .toList
      else
        Nil

    runner.withSession("--extra-class-path", extraJars.mkString(File.pathSeparator)) {
      implicit session =>
        val code   = "os.read"
        val result = inspect(code, code.length - 3, detailed = true)
        val expected = Seq(
          """<div><pre>os.read.type</pre><pre>Reads the contents of a [os.Path](os.Path) or other [os.Source](os.Source) as a
            |`java.lang.String`. Defaults to reading the entire file as UTF-8, but you can
            |also select a different `charSet` to use, and provide an `offset`/`count` to
            |read from if the source supports seeking.</pre></div>""".stripMargin
        )
        expect(result == expected)
    }
  }

}
