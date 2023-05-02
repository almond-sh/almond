package almond.integration

import almond.testkit.Dsl._
import utest._

object KernelTests extends TestSuite {

  def scalaVersion: String =
    sys.env.getOrElse(
      "TEST_SCALA_VERSION",
      sys.error("Expected TEST_SCALA_VERSION to be set")
    )

  val tests = Tests {

    test("jvm-repr") {
      KernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.jvmRepr()
      }
    }

    test("updatable display") {
      KernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.updatableDisplay()
      }
    }

    test("auto-update Future results upon completion") {
      KernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.autoUpdateFutureUponCompletion(scalaVersion)
      }
    }

    test("auto-update Future results in background upon completion") {
      KernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.autoUpdateFutureInBackgroundUponCompletion(scalaVersion)
      }
    }

    test("toree AddJar custom protocol") {
      KernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.toreeAddJarCustomProtocol(scalaVersion)
      }
    }

    test("toree custom cell magic") {
      KernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.toreeCustomCellMagic()
      }
    }

  }

}
