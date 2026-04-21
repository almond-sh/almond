package almond.integration

import almond.integration.Tests.ls
import almond.testkit.Dsl._

abstract class KernelTestsTwoStepStartupDefinitions extends AlmondFunSuite {

  def kernelLauncher: KernelLauncher

  test0("Directives and code in first cell 3") { implicit forceVersion =>
    kernelLauncher.withKernel { runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute(
          s"""//> using scala "${KernelLauncher.testScalaVersion}"
             |import scala.compiletime.ops.*
             |val sv = scala.util.Properties.versionNumberString
             |""".stripMargin,
          "import scala.compiletime.ops.*" + ls + ls +
            s"""sv: String = "${KernelLauncher.testLibraryPropertiesScalaVersion}""""
        )
      }
    }
  }

  test0("Directives and code in first cell 213") { implicit forceVersion =>
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

  test0("Directives and code in first cell 212") { implicit forceVersion =>
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

  test0("Directives and code in first cell short 213") { implicit forceVersion =>
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

  test0("Directives and code in first cell short 212") { implicit forceVersion =>
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

  test0("Several directives and comments") { implicit forceVersion =>
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

  test0("Java option on command-line") { implicit forceVersion =>
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
