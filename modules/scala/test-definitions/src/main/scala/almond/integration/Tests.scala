package almond.integration

import almond.channels.Channel
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.protocol.{Execute => ProtocolExecute, _}
import almond.testkit.Dsl._
import com.eed3si9n.expecty.Expecty.expect

import java.io.File
import java.util.UUID

import scala.jdk.CollectionConverters._
import scala.util.Properties

object Tests {

  private val sp = " "
  val ls         = System.lineSeparator()

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

  def quietOutput(consoleOut: => String, consoleErr: => String, quiet: Boolean)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit = {
    runner.withSession() { implicit session =>
      execute(
        """System.err.print("test err"); System.out.print("test out")""",
        "",
        stdout = "test out",
        stderr = "test err"
      )

      if (quiet) {
        assert(!consoleOut.contains("test out"))
        assert(!consoleErr.contains("test err"))
      }
      else {
        assert(consoleOut.contains("test out"))
        assert(consoleErr.contains("test err"))
      }
    }
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

      val isScala2 = scalaVersion.startsWith("2.")

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
          "import $cp.$" + ls + maybePostImportNewLine(isScala2) +
            "import picocli.CommandLine" + maybePostImportNewLine(isScala2),
          ignoreStreams = true,
          trimReplyLines = true
        )
      else {
        execute(
          s"%AddJar $jarUri",
          "import $cp.$" + maybePostImportNewLine(isScala2),
          ignoreStreams = true,
          trimReplyLines = true
        )

        execute(
          "import picocli.CommandLine",
          "import picocli.CommandLine" + maybePostImportNewLine(isScala2)
        )
      }
    }

  def toreeAddJarURL(scalaVersion: String, sameCell: Boolean)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit =
    runner.withSession("--toree-magics") { implicit session =>

      val isScala2 = scalaVersion.startsWith("2.")

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
          "import $cp.$" + maybePostImportNewLine(isScala2) + ls +
            "import picocli.CommandLine" + maybePostImportNewLine(isScala2),
          ignoreStreams = true,
          trimReplyLines = true
        )
      else {
        execute(
          s"%AddJar $jar",
          "import $cp.$" + maybePostImportNewLine(isScala2),
          ignoreStreams = true,
          trimReplyLines = true
        )

        execute(
          "import picocli.CommandLine",
          "import picocli.CommandLine" + maybePostImportNewLine(isScala2)
        )
      }
    }

  def toreeHtml()(implicit sessionId: SessionId, runner: Runner): Unit = {
    val launcherOptions =
      if (runner.differedStartUp)
        Seq("--shared-dependencies", "sh.almond::toree-hooks:_")
      else
        Seq("--shared", "sh.almond::toree-hooks")
    runner.withLauncherOptionsSession(launcherOptions: _*)("--toree-magics", "--toree-api") {
      implicit session =>

        execute(
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

        execute(
          """kernel.display.html("<p><b>Hello</b></p>")""",
          "",
          displaysHtml = Seq("<p><b>Hello</b></p>")
        )
    }
  }

  lazy val java17Cmd: String = {
    val isAtLeastJava17 =
      scala.util.Try(sys.props("java.version").takeWhile(_.isDigit).toInt).toOption.exists(_ >= 17)
    val javaHome =
      if (isAtLeastJava17) new File(sys.props("java.home"))
      else coursierapi.JvmManager.create().get("17")
    val ext = if (Properties.isWin) ".exe" else ""
    new File(javaHome, "bin/java" + ext).toString
  }

  lazy val scalaCliLauncher: File =
    coursierapi.Cache.create()
      .get(coursierapi.Artifact.of(
        "https://github.com/VirtusLab/scala-cli/releases/download/v1.0.1/scala-cli"
      ))

  def toreeAddJarCustomProtocol(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit = {

    val picocliJar = coursierapi.Fetch.create()
      .addDependencies(coursierapi.Dependency.of("info.picocli", "picocli", "4.7.3"))
      .withCache(
        coursierapi.Cache.create()
          .withLogger(
            coursierapi.Logger.progressBars(
              runner.output.flatMap(_.outputStreamOpt).getOrElse(System.err)
            )
          )
      )
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
      java17Cmd,
      "-jar",
      scalaCliLauncher.toString,
      "--power",
      "compile",
      "--print-class-path",
      "."
    )
      .call(
        cwd = tmpDir,
        stdin = os.Inherit,
        stderr = runner.output.map(_.processOutput).getOrElse(os.Inherit)
      )
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

        val isScala2 = scalaVersion.startsWith("2.")

        execute(
          "import picocli.CommandLine",
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          ),
          ignoreStreams = true
        )

        execute(
          "%AddJar foo://thing/a/b" + ls,
          "import $cp.$" + maybePostImportNewLine(isScala2),
          trimReplyLines = true
        )

        execute(
          "import picocli.CommandLine",
          "import picocli.CommandLine" + maybePostImportNewLine(isScala2)
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

    val launcherOptions =
      if (runner.differedStartUp)
        Seq("--shared-dependencies", "sh.almond::toree-hooks:_")
      else
        Seq("--shared", "sh.almond::toree-hooks")
    runner.withLauncherOptionsSession(launcherOptions: _*)(
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

    val sbv      = scalaVersion.split('.').take(2).mkString(".")
    val isScala2 = scalaVersion.startsWith("2.")

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
          "import shapeless._" + maybePostImportNewLine(isScala2) + ls +
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
        if (runner.differedStartUp)
          // In two step start up, we need the actual kernel to have started to get inspection results
          execute("val n = 2", "n: Int = 2")

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

  def compilationError(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit = {

    val errorOutput =
      if (scalaVersion.startsWith("2."))
        """cmd1.sc:2: not found: value foo
          |  foo
          |  ^
          |cmd1.sc:3: not found: value bar
          |  bar
          |  ^
          |cmd1.sc:4: not found: value other
          |  other
          |  ^
          |Compilation Failed""".stripMargin
      else
        """-- [E006] Not Found Error: cmd1.sc:2:2 -----------------------------------------
          |2 |  foo
          |  |  ^^^
          |  |  Not found: foo
          |  |
          |  | longer explanation available when compiling with `-explain`
          |-- [E006] Not Found Error: cmd1.sc:3:2 -----------------------------------------
          |3 |  bar
          |  |  ^^^
          |  |  Not found: bar
          |  |
          |  | longer explanation available when compiling with `-explain`
          |-- [E006] Not Found Error: cmd1.sc:4:2 -----------------------------------------
          |4 |  other
          |  |  ^^^^^
          |  |  Not found: other
          |  |
          |  | longer explanation available when compiling with `-explain`
          |Compilation Failed""".stripMargin

    runner.withSession() { implicit session =>
      execute(
        """val n = {
          |  foo
          |  bar
          |  other
          |}
          |""".stripMargin,
        expectError = true,
        stderr = errorOutput,
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        )
      )
    }
  }

  def addDependency(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit =
    runner.withSession() { implicit session =>
      val isScala2 = scalaVersion.startsWith("2.")

      execute(
        """//> using dep "org.typelevel::cats-kernel:2.6.1"
          |import cats.kernel._
          |val msg =
          |  Monoid.instance[String]("", (a, b) => a + b)
          |    .combineAll(List("Hello", "", ""))
          |""".stripMargin,
        s"""import cats.kernel._${maybePostImportNewLine(isScala2)}
           |msg: String = "Hello"""".stripMargin
      )
    }

  def addRepository(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit =
    runner.withSession() { implicit session =>

      val isScala2 = scalaVersion.startsWith("2.")

      // that repository should already have been added by Almond, so we don't test much here…
      execute(
        """//> using repository "jitpack"
          |//> using dep "com.github.jupyter:jvm-repr:0.4.0"
          |import jupyter._
          |""".stripMargin,
        "import jupyter._" + maybePostImportNewLine(isScala2)
      )
    }

  def addScalacOption(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit =
    runner.withSession() { implicit session =>
      execute(
        """@deprecated("foo", "0.1")
          |def getValue0(): Int = 2
          |val n0 = getValue0()
          |""".stripMargin,
        """defined function getValue0
          |n0: Int = 2""".stripMargin,
        ignoreStreams = true
      )

      val errorMessage =
        if (scalaVersion.startsWith("2.13."))
          """cmd2.sc:4: method getValue in class Helper is deprecated (since 0.1): foo
            |val n = getValue()
            |        ^
            |No warnings can be incurred under -Werror.
            |Compilation Failed""".stripMargin
        else if (scalaVersion.startsWith("2.12."))
          """cmd2.sc:4: method getValue in class Helper is deprecated (since 0.1): foo
            |val n = getValue()
            |        ^
            |No warnings can be incurred under -Xfatal-warnings.
            |Compilation Failed""".stripMargin
        else
          // FIXME The line number is wrong here
          """-- Error: cmd2.sc:3:8 ----------------------------------------------------------
            |3 |val n = getValue()
            |  |        ^^^^^^^^
            |  |        method getValue in class Helper is deprecated since 0.1: foo
            |Compilation Failed""".stripMargin

      execute(
        """//> using option "-Xfatal-warnings" "-deprecation"
          |@deprecated("foo", "0.1")
          |def getValue(): Int = 2
          |val n = getValue()
          |""".stripMargin,
        expectError = true,
        stderr = errorMessage,
        errors = Seq(
          ("", "Compilation Failed", List("Compilation Failed"))
        )
      )
    }

  def completion(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit =
    runner.withSession() { implicit session =>
      execute(
        "val l = 1 :: 2 :: Nil",
        "l: List[Int] = List(1, 2)"
      )
      val res = complete(
        "l.#"
      )

      val expectedMatches =
        if (scalaVersion.startsWith("2.12"))
          Seq(
            "!=",
            "++",
            "++:",
            "+:",
            ":+",
            "::",
            ":::",
            "==",
            "WithFilter",
            "addString",
            "aggregate",
            "andThen",
            "apply",
            "applyOrElse",
            "asInstanceOf",
            "canEqual",
            "collect",
            "collectFirst",
            "combinations",
            "companion",
            "compose",
            "contains",
            "containsSlice",
            "copyToArray",
            "copyToBuffer",
            "corresponds",
            "count",
            "diff",
            "distinct",
            "drop",
            "dropRight",
            "dropWhile",
            "endsWith",
            "equals",
            "exists",
            "filter",
            "filterNot",
            "find",
            "flatMap",
            "flatten",
            "fold",
            "foldLeft",
            "foldRight",
            "forall",
            "foreach",
            "genericBuilder",
            "getClass",
            "groupBy",
            "grouped",
            "hasDefiniteSize",
            "hashCode",
            "head",
            "headOption",
            "indexOf",
            "indexOfSlice",
            "indexWhere",
            "indices",
            "init",
            "inits",
            "intersect",
            "isDefinedAt",
            "isEmpty",
            "isInstanceOf",
            "isTraversableAgain",
            "iterator",
            "last",
            "lastIndexOf",
            "lastIndexOfSlice",
            "lastIndexWhere",
            "lastOption",
            "length",
            "lengthCompare",
            "lift",
            "map",
            "mapConserve",
            "max",
            "maxBy",
            "min",
            "minBy",
            "mkString",
            "nonEmpty",
            "orElse",
            "padTo",
            "par",
            "partition",
            "patch",
            "permutations",
            "prefixLength",
            "product",
            "productArity",
            "productElement",
            "productIterator",
            "productPrefix",
            "reduce",
            "reduceLeft",
            "reduceLeftOption",
            "reduceOption",
            "reduceRight",
            "reduceRightOption",
            "repr",
            "reverse",
            "reverseIterator",
            "reverseMap",
            "reverse_:::",
            "runWith",
            "sameElements",
            "scan",
            "scanLeft",
            "scanRight",
            "segmentLength",
            "seq",
            "size",
            "slice",
            "sliding",
            "sortBy",
            "sortWith",
            "sorted",
            "span",
            "splitAt",
            "startsWith",
            "stringPrefix",
            "sum",
            "tail",
            "tails",
            "take",
            "takeRight",
            "takeWhile",
            "to",
            "toArray",
            "toBuffer",
            "toIndexedSeq",
            "toIterable",
            "toIterator",
            "toList",
            "toMap",
            "toParArray",
            "toSeq",
            "toSet",
            "toStream",
            "toString",
            "toTraversable",
            "toVector",
            "transpose",
            "union",
            "unzip",
            "unzip3",
            "updated",
            "view",
            "withFilter",
            "zip",
            "zipAll",
            "zipWithIndex"
          )
        else if (scalaVersion.startsWith("2.13."))
          Seq(
            "!=",
            "++",
            "++:",
            "+:",
            ":+",
            ":++",
            "::",
            ":::",
            "==",
            "`reverse_:::`",
            "addString",
            "andThen",
            "appended",
            "appendedAll",
            "apply",
            "applyOrElse",
            "asInstanceOf",
            "canEqual",
            "collect",
            "collectFirst",
            "combinations",
            "compose",
            "concat",
            "contains",
            "containsSlice",
            "copyToArray",
            "corresponds",
            "count",
            "diff",
            "distinct",
            "distinctBy",
            "drop",
            "dropRight",
            "dropWhile",
            "elementWise",
            "empty",
            "endsWith",
            "equals",
            "exists",
            "filter",
            "filterNot",
            "find",
            "findLast",
            "flatMap",
            "flatten",
            "fold",
            "foldLeft",
            "foldRight",
            "forall",
            "foreach",
            "getClass",
            "groupBy",
            "groupMap",
            "groupMapReduce",
            "grouped",
            "hashCode",
            "head",
            "headOption",
            "indexOf",
            "indexOfSlice",
            "indexWhere",
            "indices",
            "init",
            "inits",
            "intersect",
            "isDefinedAt",
            "isEmpty",
            "isInstanceOf",
            "isTraversableAgain",
            "iterableFactory",
            "iterator",
            "knownSize",
            "last",
            "lastIndexOf",
            "lastIndexOfSlice",
            "lastIndexWhere",
            "lastOption",
            "lazyZip",
            "length",
            "lengthCompare",
            "lengthIs",
            "lift",
            "map",
            "mapConserve",
            "max",
            "maxBy",
            "maxByOption",
            "maxOption",
            "min",
            "minBy",
            "minByOption",
            "minOption",
            "mkString",
            "nonEmpty",
            "orElse",
            "padTo",
            "partition",
            "partitionMap",
            "patch",
            "permutations",
            "prepended",
            "prependedAll",
            "product",
            "reduce",
            "reduceLeft",
            "reduceLeftOption",
            "reduceOption",
            "reduceRight",
            "reduceRightOption",
            "reverse",
            "reverseIterator",
            "runWith",
            "sameElements",
            "scan",
            "scanLeft",
            "scanRight",
            "search",
            "segmentLength",
            "size",
            "sizeCompare",
            "sizeIs",
            "slice",
            "sliding",
            "sortBy",
            "sortWith",
            "sorted",
            "span",
            "splitAt",
            "startsWith",
            "stepper",
            "sum",
            "tail",
            "tails",
            "take",
            "takeRight",
            "takeWhile",
            "tapEach",
            "to",
            "toArray",
            "toBuffer",
            "toIndexedSeq",
            "toList",
            "toMap",
            "toSeq",
            "toSet",
            "toString",
            "toVector",
            "transpose",
            "unapply",
            "unzip",
            "unzip3",
            "updated",
            "view",
            "withFilter",
            "zip",
            "zipAll",
            "zipWithIndex"
          )
        else
          Seq(
            "!=",
            "++",
            "++:",
            "+:",
            "/:",
            ":+",
            ":++",
            "::",
            ":::",
            ":\\",
            "==",
            "CombinationsItr",
            "Maximized",
            "PermutationsItr",
            "addString",
            "aggregate",
            "andThen",
            "appended",
            "appendedAll",
            "apply",
            "applyOrElse",
            "asInstanceOf",
            "canEqual",
            "collect",
            "collectFirst",
            "combinations",
            "companion",
            "compose",
            "concat",
            "contains",
            "containsSlice",
            "copyToArray",
            "copyToBuffer",
            "corresponds",
            "count",
            "diff",
            "distinct",
            "distinctBy",
            "drop",
            "dropRight",
            "dropWhile",
            "elementWise",
            "empty",
            "endsWith",
            "equals",
            "exists",
            "filter",
            "filterNot",
            "find",
            "findLast",
            "flatMap",
            "flatten",
            "fold",
            "foldLeft",
            "foldRight",
            "forall",
            "foreach",
            "getClass",
            "groupBy",
            "groupMap",
            "groupMapReduce",
            "grouped",
            "hasDefiniteSize",
            "hashCode",
            "head",
            "headOption",
            "indexOf",
            "indexOfSlice",
            "indexWhere",
            "indices",
            "init",
            "inits",
            "intersect",
            "isDefinedAt",
            "isEmpty",
            "isInstanceOf",
            "isTraversableAgain",
            "iterableFactory",
            "iterator",
            "knownSize",
            "last",
            "lastIndexOf",
            "lastIndexOfSlice",
            "lastIndexWhere",
            "lastOption",
            "lazyZip",
            "length",
            "lengthCompare",
            "lengthIs",
            "lift",
            "map",
            "mapConserve",
            "max",
            "maxBy",
            "maxByOption",
            "maxOption",
            "min",
            "minBy",
            "minByOption",
            "minOption",
            "mkString",
            "nonEmpty",
            "orElse",
            "padTo",
            "partition",
            "partitionMap",
            "patch",
            "permutations",
            "prefixLength",
            "prepended",
            "prependedAll",
            "product",
            "reduce",
            "reduceLeft",
            "reduceLeftOption",
            "reduceOption",
            "reduceRight",
            "reduceRightOption",
            "repr",
            "reverse",
            "reverseIterator",
            "reverseMap",
            "reverse_:::",
            "runWith",
            "sameElements",
            "scan",
            "scanLeft",
            "scanRight",
            "search",
            "segmentLength",
            "seq",
            "size",
            "sizeCompare",
            "sizeIs",
            "slice",
            "sliding",
            "sortBy",
            "sortWith",
            "sorted",
            "span",
            "splitAt",
            "startsWith",
            "stepper",
            "sum",
            "tail",
            "tails",
            "take",
            "takeRight",
            "takeWhile",
            "tapEach",
            "to",
            "toArray",
            "toBuffer",
            "toIndexedSeq",
            "toIterable",
            "toIterator",
            "toList",
            "toMap",
            "toSeq",
            "toSet",
            "toStream",
            "toString",
            "toTraversable",
            "toVector",
            "transpose",
            "unapply",
            "union",
            "unzip",
            "unzip3",
            "updated",
            "view",
            "withFilter",
            "zip",
            "zipAll",
            "zipWithIndex"
          )
      val matches = res.flatMap(_.matches)
      if (matches != expectedMatches) {
        pprint.err.log(expectedMatches)
        pprint.err.log(matches)
      }
      expect(matches == expectedMatches)

      val expectedTypes =
        if (scalaVersion.startsWith("2.12."))
          Seq(
            ("!=", "Method"),
            ("++", "Method"),
            ("++:", "Method"),
            ("+:", "Method"),
            (":+", "Method"),
            ("::", "Method"),
            (":::", "Method"),
            ("==", "Method"),
            ("WithFilter", "Class"),
            ("addString", "Method"),
            ("aggregate", "Method"),
            ("andThen", "Method"),
            ("apply", "Method"),
            ("applyOrElse", "Method"),
            ("asInstanceOf", "Method"),
            ("canEqual", "Method"),
            ("collect", "Method"),
            ("collectFirst", "Method"),
            ("combinations", "Method"),
            ("companion", "Method"),
            ("compose", "Method"),
            ("contains", "Method"),
            ("containsSlice", "Method"),
            ("copyToArray", "Method"),
            ("copyToBuffer", "Method"),
            ("corresponds", "Method"),
            ("count", "Method"),
            ("diff", "Method"),
            ("distinct", "Method"),
            ("drop", "Method"),
            ("dropRight", "Method"),
            ("dropWhile", "Method"),
            ("endsWith", "Method"),
            ("equals", "Method"),
            ("exists", "Method"),
            ("filter", "Method"),
            ("filterNot", "Method"),
            ("find", "Method"),
            ("flatMap", "Method"),
            ("flatten", "Method"),
            ("fold", "Method"),
            ("foldLeft", "Method"),
            ("foldRight", "Method"),
            ("forall", "Method"),
            ("foreach", "Method"),
            ("genericBuilder", "Method"),
            ("getClass", "Method"),
            ("groupBy", "Method"),
            ("grouped", "Method"),
            ("hasDefiniteSize", "Method"),
            ("hashCode", "Method"),
            ("head", "Method"),
            ("headOption", "Method"),
            ("indexOf", "Method"),
            ("indexOfSlice", "Method"),
            ("indexWhere", "Method"),
            ("indices", "Method"),
            ("init", "Method"),
            ("inits", "Method"),
            ("intersect", "Method"),
            ("isDefinedAt", "Method"),
            ("isEmpty", "Method"),
            ("isInstanceOf", "Method"),
            ("isTraversableAgain", "Method"),
            ("iterator", "Method"),
            ("last", "Method"),
            ("lastIndexOf", "Method"),
            ("lastIndexOfSlice", "Method"),
            ("lastIndexWhere", "Method"),
            ("lastOption", "Method"),
            ("length", "Method"),
            ("lengthCompare", "Method"),
            ("lift", "Method"),
            ("map", "Method"),
            ("mapConserve", "Method"),
            ("max", "Method"),
            ("maxBy", "Method"),
            ("min", "Method"),
            ("minBy", "Method"),
            ("mkString", "Method"),
            ("nonEmpty", "Method"),
            ("orElse", "Method"),
            ("padTo", "Method"),
            ("par", "Method"),
            ("partition", "Method"),
            ("patch", "Method"),
            ("permutations", "Method"),
            ("prefixLength", "Method"),
            ("product", "Method"),
            ("productArity", "Method"),
            ("productElement", "Method"),
            ("productIterator", "Method"),
            ("productPrefix", "Method"),
            ("reduce", "Method"),
            ("reduceLeft", "Method"),
            ("reduceLeftOption", "Method"),
            ("reduceOption", "Method"),
            ("reduceRight", "Method"),
            ("reduceRightOption", "Method"),
            ("repr", "Method"),
            ("reverse", "Method"),
            ("reverseIterator", "Method"),
            ("reverseMap", "Method"),
            ("reverse_:::", "Method"),
            ("runWith", "Method"),
            ("sameElements", "Method"),
            ("scan", "Method"),
            ("scanLeft", "Method"),
            ("scanRight", "Method"),
            ("segmentLength", "Method"),
            ("seq", "Method"),
            ("size", "Method"),
            ("slice", "Method"),
            ("sliding", "Method"),
            ("sortBy", "Method"),
            ("sortWith", "Method"),
            ("sorted", "Method"),
            ("span", "Method"),
            ("splitAt", "Method"),
            ("startsWith", "Method"),
            ("stringPrefix", "Method"),
            ("sum", "Method"),
            ("tail", "Method"),
            ("tails", "Method"),
            ("take", "Method"),
            ("takeRight", "Method"),
            ("takeWhile", "Method"),
            ("to", "Method"),
            ("toArray", "Method"),
            ("toBuffer", "Method"),
            ("toIndexedSeq", "Method"),
            ("toIterable", "Method"),
            ("toIterator", "Method"),
            ("toList", "Method"),
            ("toMap", "Method"),
            ("toParArray", "Method"),
            ("toSeq", "Method"),
            ("toSet", "Method"),
            ("toStream", "Method"),
            ("toString", "Method"),
            ("toTraversable", "Method"),
            ("toVector", "Method"),
            ("transpose", "Method"),
            ("union", "Method"),
            ("unzip", "Method"),
            ("unzip3", "Method"),
            ("updated", "Method"),
            ("view", "Method"),
            ("withFilter", "Method"),
            ("zip", "Method"),
            ("zipAll", "Method"),
            ("zipWithIndex", "Method")
          )
        else if (scalaVersion.startsWith("2.13."))
          Seq(
            ("!=", "Method"),
            ("++", "Method"),
            ("++:", "Method"),
            ("+:", "Method"),
            (":+", "Method"),
            (":++", "Method"),
            ("::", "Method"),
            (":::", "Method"),
            ("==", "Method"),
            ("`reverse_:::`", "Method"),
            ("addString", "Method"),
            ("andThen", "Method"),
            ("appended", "Method"),
            ("appendedAll", "Method"),
            ("apply", "Method"),
            ("applyOrElse", "Method"),
            ("asInstanceOf", "Method"),
            ("canEqual", "Method"),
            ("collect", "Method"),
            ("collectFirst", "Method"),
            ("combinations", "Method"),
            ("compose", "Method"),
            ("concat", "Method"),
            ("contains", "Method"),
            ("containsSlice", "Method"),
            ("copyToArray", "Method"),
            ("corresponds", "Method"),
            ("count", "Method"),
            ("diff", "Method"),
            ("distinct", "Method"),
            ("distinctBy", "Method"),
            ("drop", "Method"),
            ("dropRight", "Method"),
            ("dropWhile", "Method"),
            ("elementWise", "Method"),
            ("empty", "Method"),
            ("endsWith", "Method"),
            ("equals", "Method"),
            ("exists", "Method"),
            ("filter", "Method"),
            ("filterNot", "Method"),
            ("find", "Method"),
            ("findLast", "Method"),
            ("flatMap", "Method"),
            ("flatten", "Method"),
            ("fold", "Method"),
            ("foldLeft", "Method"),
            ("foldRight", "Method"),
            ("forall", "Method"),
            ("foreach", "Method"),
            ("getClass", "Method"),
            ("groupBy", "Method"),
            ("groupMap", "Method"),
            ("groupMapReduce", "Method"),
            ("grouped", "Method"),
            ("hashCode", "Method"),
            ("head", "Method"),
            ("headOption", "Method"),
            ("indexOf", "Method"),
            ("indexOfSlice", "Method"),
            ("indexWhere", "Method"),
            ("indices", "Method"),
            ("init", "Method"),
            ("inits", "Method"),
            ("intersect", "Method"),
            ("isDefinedAt", "Method"),
            ("isEmpty", "Method"),
            ("isInstanceOf", "Method"),
            ("isTraversableAgain", "Method"),
            ("iterableFactory", "Method"),
            ("iterator", "Method"),
            ("knownSize", "Method"),
            ("last", "Method"),
            ("lastIndexOf", "Method"),
            ("lastIndexOfSlice", "Method"),
            ("lastIndexWhere", "Method"),
            ("lastOption", "Method"),
            ("lazyZip", "Method"),
            ("length", "Method"),
            ("lengthCompare", "Method"),
            ("lengthIs", "Method"),
            ("lift", "Method"),
            ("map", "Method"),
            ("mapConserve", "Method"),
            ("max", "Method"),
            ("maxBy", "Method"),
            ("maxByOption", "Method"),
            ("maxOption", "Method"),
            ("min", "Method"),
            ("minBy", "Method"),
            ("minByOption", "Method"),
            ("minOption", "Method"),
            ("mkString", "Method"),
            ("nonEmpty", "Method"),
            ("orElse", "Method"),
            ("padTo", "Method"),
            ("partition", "Method"),
            ("partitionMap", "Method"),
            ("patch", "Method"),
            ("permutations", "Method"),
            ("prepended", "Method"),
            ("prependedAll", "Method"),
            ("product", "Method"),
            ("reduce", "Method"),
            ("reduceLeft", "Method"),
            ("reduceLeftOption", "Method"),
            ("reduceOption", "Method"),
            ("reduceRight", "Method"),
            ("reduceRightOption", "Method"),
            ("reverse", "Method"),
            ("reverseIterator", "Method"),
            ("runWith", "Method"),
            ("sameElements", "Method"),
            ("scan", "Method"),
            ("scanLeft", "Method"),
            ("scanRight", "Method"),
            ("search", "Method"),
            ("segmentLength", "Method"),
            ("size", "Method"),
            ("sizeCompare", "Method"),
            ("sizeIs", "Method"),
            ("slice", "Method"),
            ("sliding", "Method"),
            ("sortBy", "Method"),
            ("sortWith", "Method"),
            ("sorted", "Method"),
            ("span", "Method"),
            ("splitAt", "Method"),
            ("startsWith", "Method"),
            ("stepper", "Method"),
            ("sum", "Method"),
            ("tail", "Method"),
            ("tails", "Method"),
            ("take", "Method"),
            ("takeRight", "Method"),
            ("takeWhile", "Method"),
            ("tapEach", "Method"),
            ("to", "Method"),
            ("toArray", "Method"),
            ("toBuffer", "Method"),
            ("toIndexedSeq", "Method"),
            ("toList", "Method"),
            ("toMap", "Method"),
            ("toSeq", "Method"),
            ("toSet", "Method"),
            ("toString", "Method"),
            ("toVector", "Method"),
            ("transpose", "Method"),
            ("unapply", "Method"),
            ("unzip", "Method"),
            ("unzip3", "Method"),
            ("updated", "Method"),
            ("view", "Method"),
            ("withFilter", "Method"),
            ("zip", "Method"),
            ("zipAll", "Method"),
            ("zipWithIndex", "Method")
          )
        else
          Seq(
            ("!=", "Method"),
            ("++", "Method"),
            ("++:", "Method"),
            ("+:", "Method"),
            ("/:", "Method"),
            (":+", "Method"),
            (":++", "Method"),
            ("::", "Method"),
            (":::", "Method"),
            (":\\", "Method"),
            ("==", "Method"),
            ("CombinationsItr", "Object"),
            ("Maximized", "Object"),
            ("PermutationsItr", "Object"),
            ("addString", "Method"),
            ("addString", "Method"),
            ("addString", "Method"),
            ("aggregate", "Method"),
            ("andThen", "Method"),
            ("andThen", "Method"),
            ("appended", "Method"),
            ("appendedAll", "Method"),
            ("apply", "Method"),
            ("applyOrElse", "Method"),
            ("asInstanceOf", "Method"),
            ("canEqual", "Method"),
            ("collect", "Method"),
            ("collectFirst", "Method"),
            ("combinations", "Method"),
            ("companion", "Method"),
            ("compose", "Method"),
            ("compose", "Method"),
            ("concat", "Method"),
            ("contains", "Method"),
            ("containsSlice", "Method"),
            ("copyToArray", "Method"),
            ("copyToArray", "Method"),
            ("copyToArray", "Method"),
            ("copyToBuffer", "Method"),
            ("corresponds", "Method"),
            ("corresponds", "Method"),
            ("count", "Method"),
            ("diff", "Method"),
            ("distinct", "Method"),
            ("distinctBy", "Method"),
            ("drop", "Method"),
            ("dropRight", "Method"),
            ("dropWhile", "Method"),
            ("elementWise", "Method"),
            ("empty", "Method"),
            ("endsWith", "Method"),
            ("equals", "Method"),
            ("exists", "Method"),
            ("filter", "Method"),
            ("filterNot", "Method"),
            ("find", "Method"),
            ("findLast", "Method"),
            ("flatMap", "Method"),
            ("flatten", "Method"),
            ("fold", "Method"),
            ("foldLeft", "Method"),
            ("foldRight", "Method"),
            ("forall", "Method"),
            ("foreach", "Method"),
            ("getClass", "Method"),
            ("groupBy", "Method"),
            ("groupMap", "Method"),
            ("groupMapReduce", "Method"),
            ("grouped", "Method"),
            ("hasDefiniteSize", "Method"),
            ("hashCode", "Method"),
            ("head", "Method"),
            ("headOption", "Method"),
            ("indexOf", "Method"),
            ("indexOf", "Method"),
            ("indexOfSlice", "Method"),
            ("indexOfSlice", "Method"),
            ("indexWhere", "Method"),
            ("indexWhere", "Method"),
            ("indices", "Method"),
            ("init", "Method"),
            ("inits", "Method"),
            ("intersect", "Method"),
            ("isDefinedAt", "Method"),
            ("isEmpty", "Method"),
            ("isInstanceOf", "Method"),
            ("isTraversableAgain", "Method"),
            ("iterableFactory", "Method"),
            ("iterator", "Method"),
            ("knownSize", "Method"),
            ("last", "Method"),
            ("lastIndexOf", "Method"),
            ("lastIndexOfSlice", "Method"),
            ("lastIndexOfSlice", "Method"),
            ("lastIndexWhere", "Method"),
            ("lastIndexWhere", "Method"),
            ("lastOption", "Method"),
            ("lazyZip", "Method"),
            ("length", "Method"),
            ("lengthCompare", "Method"),
            ("lengthCompare", "Method"),
            ("lengthIs", "Method"),
            ("lift", "Method"),
            ("map", "Method"),
            ("mapConserve", "Method"),
            ("max", "Method"),
            ("maxBy", "Method"),
            ("maxByOption", "Method"),
            ("maxOption", "Method"),
            ("min", "Method"),
            ("minBy", "Method"),
            ("minByOption", "Method"),
            ("minOption", "Method"),
            ("mkString", "Method"),
            ("mkString", "Method"),
            ("mkString", "Method"),
            ("nonEmpty", "Method"),
            ("orElse", "Method"),
            ("padTo", "Method"),
            ("partition", "Method"),
            ("partitionMap", "Method"),
            ("patch", "Method"),
            ("permutations", "Method"),
            ("prefixLength", "Method"),
            ("prepended", "Method"),
            ("prependedAll", "Method"),
            ("product", "Method"),
            ("reduce", "Method"),
            ("reduceLeft", "Method"),
            ("reduceLeftOption", "Method"),
            ("reduceOption", "Method"),
            ("reduceRight", "Method"),
            ("reduceRightOption", "Method"),
            ("repr", "Method"),
            ("reverse", "Method"),
            ("reverseIterator", "Method"),
            ("reverseMap", "Method"),
            ("reverse_:::", "Method"),
            ("runWith", "Method"),
            ("sameElements", "Method"),
            ("scan", "Method"),
            ("scanLeft", "Method"),
            ("scanRight", "Method"),
            ("search", "Method"),
            ("search", "Method"),
            ("segmentLength", "Method"),
            ("segmentLength", "Method"),
            ("seq", "Method"),
            ("size", "Method"),
            ("sizeCompare", "Method"),
            ("sizeCompare", "Method"),
            ("sizeIs", "Method"),
            ("slice", "Method"),
            ("sliding", "Method"),
            ("sliding", "Method"),
            ("sortBy", "Method"),
            ("sortWith", "Method"),
            ("sorted", "Method"),
            ("span", "Method"),
            ("splitAt", "Method"),
            ("startsWith", "Method"),
            ("stepper", "Method"),
            ("sum", "Method"),
            ("tail", "Method"),
            ("tails", "Method"),
            ("take", "Method"),
            ("takeRight", "Method"),
            ("takeWhile", "Method"),
            ("tapEach", "Method"),
            ("to", "Method"),
            ("toArray", "Method"),
            ("toBuffer", "Method"),
            ("toIndexedSeq", "Method"),
            ("toIterable", "Method"),
            ("toIterator", "Method"),
            ("toList", "Method"),
            ("toMap", "Method"),
            ("toSeq", "Method"),
            ("toSet", "Method"),
            ("toStream", "Method"),
            ("toString", "Method"),
            ("toTraversable", "Method"),
            ("toVector", "Method"),
            ("transpose", "Method"),
            ("unapply", "Method"),
            ("union", "Method"),
            ("unzip", "Method"),
            ("unzip3", "Method"),
            ("updated", "Method"),
            ("view", "Method"),
            ("view", "Method"),
            ("withFilter", "Method"),
            ("zip", "Method"),
            ("zipAll", "Method"),
            ("zipWithIndex", "Method")
          )

      val metadata = ujson.read(res.head.metadata.value)
      val types    = metadata.obj("_jupyter_types_experimental")
      val types0 = types.arr.map { entry =>
        val entry0 = entry.obj
        (entry0("text").str, entry0("type").str)
      }
      if (types0 != expectedTypes) {
        pprint.err.log(expectedTypes)
        pprint.err.log(types0)
      }
      expect(types0 == expectedTypes)
    }
}
