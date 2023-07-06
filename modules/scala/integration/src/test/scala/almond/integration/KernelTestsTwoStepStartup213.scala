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

}
