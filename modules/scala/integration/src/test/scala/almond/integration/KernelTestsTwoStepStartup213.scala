package almond.integration

import almond.testkit.Dsl._

import scala.util.Properties

class KernelTestsTwoStepStartup213 extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Jvm, KernelLauncher.testScala213Version)

  test("mixed directives") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>

        execute(
          s"""//> using scala "${KernelLauncher.testScala213Version}"
             |2
             |""".stripMargin,
          "res1: Int = 2"
        )
        execute(
          """//> using option "-deprecation" "-Xfatal-warnings"
            |
            |@deprecated
            |def foo() = 2
            |
            |foo()
            |""".stripMargin,
          expectError = true,
          stderr =
            """cmd2.sc:6: method foo in class Helper is deprecated
              |val res2_1 = foo()
              |             ^
              |No warnings can be incurred under -Werror.
              |Compilation Failed""".stripMargin,
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          )
        )
      }
    }
  }

  test("mixed directives in first cell") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>

        execute(
          s"""//> using scala "${KernelLauncher.testScala213Version}"
             |//> using option "-deprecation" "-Xfatal-warnings"
             |2
             |""".stripMargin,
          "res1: Int = 2"
        )
        execute(
          """@deprecated
            |def foo() = 2
            |
            |foo()
            |""".stripMargin,
          expectError = true,
          stderr =
            """cmd2.sc:4: method foo in class Helper is deprecated
              |val res2_1 = foo()
              |             ^
              |No warnings can be incurred under -Werror.
              |Compilation Failed""".stripMargin,
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          )
        )
      }
    }
  }

  test("mixed directives single cell") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>

        execute(
          s"""//> using scala "${KernelLauncher.testScala213Version}"
             |//> using option "-deprecation" "-Xfatal-warnings"
             |
             |@deprecated
             |def foo() = 2
             |
             |foo()
             |""".stripMargin,
          expectError = true,
          stderr =
            """cmd1.sc:7: method foo in class Helper is deprecated
              |val res1_1 = foo()
              |             ^
              |No warnings can be incurred under -Werror.
              |Compilation Failed""".stripMargin,
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          )
        )
      }
    }
  }

  test("mixed directives several kernel options") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>

        execute(
          """//> using scala "2.12.19"
            |//> using option "-Xfatal-warnings"
            |""".stripMargin,
          ""
        )

        execute(
          """//> using option "-deprecation"
            |
            |@deprecated
            |def foo() = 2
            |
            |foo()
            |""".stripMargin,
          expectError = true,
          stderr =
            """cmd2.sc:6: method foo in class Helper is deprecated
              |val res2_1 = foo()
              |             ^
              |No warnings can be incurred under -Xfatal-warnings.
              |Compilation Failed""".stripMargin,
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          )
        )
      }
    }
  }

  test("late launcher directives") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>

        execute(
          """//> using scala "2.12.19"
            |//> using option "-Xfatal-warnings"
            |""".stripMargin,
          ""
        )

        execute(
          """//> using option "-deprecation"
            |
            |@deprecated
            |def foo() = 2
            |
            |foo()
            |""".stripMargin,
          expectError = true,
          stderr =
            """cmd2.sc:6: method foo in class Helper is deprecated
              |val res2_1 = foo()
              |             ^
              |No warnings can be incurred under -Xfatal-warnings.
              |Compilation Failed""".stripMargin,
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          )
        )

        execute(
          s"""//> using scala "${KernelLauncher.testScala213Version}"""",
          "",
          stderr =
            """Warning: ignoring 1 directive(s) that can only be used prior to any code:
              |  //> using scala
              |""".stripMargin
        )
      }
    }
  }

  test("custom startup directives") {

    val tmpDir = os.temp.dir(prefix = "almond-custom-directives-test-")

    val handlerCode =
      s"""//> using lib "com.lihaoyi::os-lib:0.9.1"
         |//> using lib "com.lihaoyi::pprint:0.8.1"
         |//> using lib "com.lihaoyi::upickle:3.1.0"
         |//> using scala "${KernelLauncher.testScala213Version}"
         |//> using jvm "8"
         |
         |package handler
         |
         |object Handler {
         |  case class Entry(key: String, values: List[String] = Nil)
         |  case class LauncherParams(scala: String = "")
         |  case class KernelParams(dependencies: List[String] = Nil)
         |  case class Input(
         |    entries: List[Entry] = Nil,
         |    currentLauncherParameters: LauncherParams = LauncherParams(),
         |    currentKernelParameters: KernelParams = KernelParams()
         |  )
         |  implicit val entryCodec: upickle.default.ReadWriter[Entry] = upickle.default.macroRW
         |  implicit val launcherParamsCodec: upickle.default.ReadWriter[LauncherParams] = upickle.default.macroRW
         |  implicit val kernelParamsCodec: upickle.default.ReadWriter[KernelParams] = upickle.default.macroRW
         |  implicit val inputCodec: upickle.default.ReadWriter[Input] = upickle.default.macroRW
         |
         |  def main(args: Array[String]): Unit = {
         |    assert(args.length == 1, "Usage: handle-spark-directives input.json")
         |    pprint.err.log(os.read(os.Path(args(0), os.pwd)))
         |    val input = upickle.default.read[Input](os.read.bytes(os.Path(args(0), os.pwd)))
         |    pprint.err.log(input)
         |
         |    val version = input.entries.find(_.key == "spark.version").flatMap(_.values.headOption).getOrElse("X.Y")
         |    val thing = input.entries.find(_.key == "spark.thing").flatMap(_.values.headOption).getOrElse("no thing")
         |    val sv = Some(input.currentLauncherParameters.scala).filter(_.nonEmpty)
         |      .getOrElse("3.3-test")
         |
         |    val output = ujson.Obj(
         |      "launcherParameters" -> ujson.Obj(
         |        "javaCmd" -> ujson.Arr(Seq(
         |          "java", s"-Dthe-version=$$version", s"-Dthe-not-scala-version=not-$$sv", s"-Dthe-thing=$$thing"
         |        ).map(ujson.Str(_)): _*)
         |      )
         |    )
         |
         |    println(output.render())
         |  }
         |}
         |""".stripMargin

    val directivesHandler = {
      val ext = if (Properties.isWin) ".bat" else ""
      tmpDir / s"handle-spark-directives$ext"
    }

    os.write(tmpDir / "Handler.scala", handlerCode)

    kernelLauncher.withKernel { implicit runner =>

      os.proc(
        Tests.java17Cmd,
        "-jar",
        Tests.scalaCliLauncher.toString,
        "--power",
        "package",
        ".",
        "-o",
        directivesHandler
      ).call(cwd = tmpDir, stderr = runner.output.map(_.processOutput).getOrElse(os.Inherit))

      implicit val sessionId: SessionId = SessionId()
      runner.withSession("--custom-directive-group", s"spark:$directivesHandler") {
        implicit session =>
          execute(
            s"""//> using spark.version "1.2.3"
               |//> using spark.thing "foo"
               |//> using spark
               |//> using scala "${KernelLauncher.testScala213Version}"
               |""".stripMargin,
            ""
          )

          execute(
            """val version = sys.props.getOrElse("the-version", "nope")
              |val thing = sys.props.getOrElse("the-thing", "nope")
              |val notScalaVersion = sys.props.getOrElse("the-not-scala-version", "nope")
              |""".stripMargin,
            s"""version: String = "1.2.3"
               |thing: String = "foo"
               |notScalaVersion: String = "not-${KernelLauncher.testScala213Version}"""".stripMargin
          )
      }
    }
  }

}
