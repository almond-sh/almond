package almond.integration

import almond.testkit.Dsl._

class KernelTestsTwoStepStartup213 extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Jvm, KernelLauncher.testScala213Version)

  test("mixed directives") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>

        execute(
          """//> using scala "2.13.11"
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
            """cell2.sc:4: method foo in class Helper is deprecated
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
          """//> using scala "2.13.11"
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
            """cell2.sc:4: method foo in class Helper is deprecated
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
          """//> using scala "2.13.11"
            |//> using option "-deprecation" "-Xfatal-warnings"
            |
            |@deprecated
            |def foo() = 2
            |
            |foo()
            |""".stripMargin,
          expectError = true,
          stderr =
            """cell1.sc:4: method foo in class Helper is deprecated
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
          """//> using scala "2.12.18"
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
            """cell2.sc:4: method foo in class Helper is deprecated
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
          """//> using scala "2.12.18"
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
            """cell2.sc:4: method foo in class Helper is deprecated
              |val res2_1 = foo()
              |             ^
              |No warnings can be incurred under -Xfatal-warnings.
              |Compilation Failed""".stripMargin,
          errors = Seq(
            ("", "Compilation Failed", List("Compilation Failed"))
          )
        )

        execute(
          """//> using scala "2.13.11"""",
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
      """//> using lib "com.lihaoyi::os-lib:0.9.1"
        |//> using lib "com.lihaoyi::pprint:0.8.1"
        |//> using lib "com.lihaoyi::upickle:3.1.0"
        |//> using scala "2.13.11"
        |//> using jvm "8"
        |
        |package handler
        |
        |object Handler {
        |  case class Entry(key: String, values: List[String])
        |  implicit val entryCodec: upickle.default.ReadWriter[Entry] = upickle.default.macroRW
        |
        |  def main(args: Array[String]): Unit = {
        |    assert(args.length == 1, "Usage: handle-spark-directives input.json")
        |    val inputEntries = upickle.default.read[List[Entry]](os.read.bytes(os.Path(args(0), os.pwd)))
        |    pprint.err.log(inputEntries)
        |
        |    val version = inputEntries.find(_.key == "spark.version").flatMap(_.values.headOption).getOrElse("X.Y")
        |
        |    val output = ujson.Obj(
        |      "javaCmd" -> ujson.Arr(Seq("java", s"-Dthe-version=$version").map(ujson.Str(_)): _*)
        |    )
        |
        |    println(output.render())
        |  }
        |}
        |""".stripMargin

    val directivesHandler = tmpDir / "handle-spark-directives"

    os.write(tmpDir / "Handler.scala", handlerCode)

    os.proc(
      Tests.java17Cmd,
      "-jar",
      Tests.scalaCliLauncher.toString,
      "--power",
      "package",
      ".",
      "-o",
      directivesHandler
    ).call(cwd = tmpDir)

    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession("--custom-directive-group", s"spark:$directivesHandler") {
        implicit session =>
          execute(
            """//> using spark.version "1.2.3"
              |//> using spark
              |""".stripMargin,
            ""
          )

          execute(
            """val version = sys.props.getOrElse("the-version", "nope")""",
            """version: String = "1.2.3""""
          )
      }
    }
  }

}
