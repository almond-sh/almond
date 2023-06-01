package almond.integration

import almond.integration.Tests.ls
import almond.testkit.Dsl._

import scala.util.Properties

abstract class KernelTestsTwoStepStartupDefinitions extends AlmondFunSuite {

  def kernelLauncher: KernelLauncher

  def directivesAndCodeInFirstCell3Test(): Unit =
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

  // not sure why, this one fails on the Windows CI with an error like
  //
  //   expect(replies == Option(reply).toVector)
  //          |       |  |      |      |
  //          |       |  |      |      Vector(import scala.compiletime.ops.*
  //
  //   sv: String = "2.13.10")
  //          |       |  |      import scala.compiletime.ops.*
  //
  //   sv: String = "2.13.10"
  //          |       |  Some(import scala.compiletime.ops.*
  //
  //   sv: String = "2.13.10")
  //          |       false
  //          Vector(import scala.compiletime.ops.*
  //
  //   sv: String = "2.13.10")
  //
  // while the replies seem identical at first sight:
  //
  //   D:\a\almond\almond\modules\shared\test-kit\src\main\scala\almond\testkit\Dsl.scala:166 expectedSingleReply: """import scala.compiletime.ops.*
  //
  //   sv: String = "2.13.10""""
  //   D:\a\almond\almond\modules\shared\test-kit\src\main\scala\almond\testkit\Dsl.scala:167 gotReplies: Vector(
  //     """import scala.compiletime.ops.*
  //
  //   sv: String = "2.13.10""""
  //   )
  //
  // I couldn't reproduce that error on a Windows VM - the test just passes there
  if (kernelLauncher.launcherType != KernelLauncher.LauncherType.Native || !Properties.isWin)
    test("Directives and code in first cell 3") {
      directivesAndCodeInFirstCell3Test()
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
