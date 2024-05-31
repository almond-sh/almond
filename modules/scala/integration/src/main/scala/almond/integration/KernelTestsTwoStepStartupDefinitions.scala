package almond.integration

import almond.integration.Tests.ls
import almond.testkit.Dsl._

abstract class KernelTestsTwoStepStartupDefinitions extends AlmondFunSuite {

  def kernelLauncher: KernelLauncher

  test("Directives and code in first cell 3") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          s"""//> using scala "${KernelLauncher.testScalaVersion}"
             |import scala.compiletime.ops.*
             |val sv = scala.util.Properties.versionNumberString
             |""".stripMargin,
          "import scala.compiletime.ops.*" + ls + ls +
            s"""sv: String = "${KernelLauncher.testScala213VersionPulledByScala3}""""
        )
      }
    }
  }

  test("Directives and code in first cell 213") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          s"""//> using scala "${KernelLauncher.testScala213Version}"
             |val sv = scala.util.Properties.versionNumberString
             |""".stripMargin,
          s"""sv: String = "${KernelLauncher.testScala213Version}""""
        )
      }
    }
  }

  test("Directives and code in first cell 212") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          s"""//> using scala "${KernelLauncher.testScala212Version}"
             |val sv = scala.util.Properties.versionNumberString
             |""".stripMargin,
          s"""sv: String = "${KernelLauncher.testScala212Version}""""
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
          s"""sv: String = "${KernelLauncher.testScala213Version}""""
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
          s"""sv: String = "${KernelLauncher.testScala212Version}""""
        )
      }
    }
  }

  test("Several directives and comments") {
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          s"""//> using scala "${KernelLauncher.testScalaVersion}"""",
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
          s"""//> using scala "${KernelLauncher.testScalaVersion}"
             |val foo = sys.props("foo")""".stripMargin,
          """foo: String = "thing""""
        )
      }
    }
  }

}
