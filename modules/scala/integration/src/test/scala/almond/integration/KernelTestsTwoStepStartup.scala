package almond.integration

import almond.integration.Tests.ls
import almond.testkit.Dsl._

class KernelTestsTwoStepStartup extends munit.FunSuite {

  val kernelLauncher = new KernelLauncher(isTwoStepStartup = true, "2.13.10")

  test("Directives and code in first cell 3") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          """//> using scala "3.2.2"
            |import scala.compiletime.ops.*
            |val sv = scala.util.Properties.versionNumberString
            |""".stripMargin,
          "import scala.compiletime.ops.*" + ls + ls +
            """sv: String = "2.13.10""""
        )
      }
    }
  }

  test("Directives and code in first cell 213") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          """//> using scala "2.13.10"
            |val sv = scala.util.Properties.versionNumberString
            |""".stripMargin,
          """sv: String = "2.13.10""""
        )
      }
    }
  }

  test("Directives and code in first cell 212") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          """//> using scala "2.12.17"
            |val sv = scala.util.Properties.versionNumberString
            |""".stripMargin,
          """sv: String = "2.12.17""""
        )
      }
    }
  }

  test("Directives and code in first cell short 213") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          """//> using scala "2.13"
            |val sv = scala.util.Properties.versionNumberString
            |""".stripMargin,
          """sv: String = "2.13.10""""
        )
      }
    }
  }

  test("Directives and code in first cell short 212") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          """//> using scala "2.12"
            |val sv = scala.util.Properties.versionNumberString
            |""".stripMargin,
          """sv: String = "2.12.17""""
        )
      }
    }
  }

  test("Several directives and comments") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          """//> using scala "3.2.2"""",
          ""
        )
        execute(
          """//> using javaOpt "-Dfoo=bar"""",
          ""
        )
        execute(
          """val foo = sys.props("foo")""",
          """foo: String = "bar""""
        )
      }
    }
  }

  test("Java option on command-line") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession("--java-opt", "-Dfoo=thing") { implicit session =>
        execute(
          """//> using scala "3.2.2"
            |val foo = sys.props("foo")""".stripMargin,
          """foo: String = "thing""""
        )
      }
    }
  }

}
