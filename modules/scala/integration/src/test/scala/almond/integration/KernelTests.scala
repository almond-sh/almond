package almond.integration

import almond.testkit.Dsl._

class KernelTests extends munit.FunSuite {

  def scalaVersion: String =
    sys.env.getOrElse(
      "TEST_SCALA_VERSION",
      sys.error("Expected TEST_SCALA_VERSION to be set")
    )

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

  test("toree AddJar file") {
    KernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(scalaVersion, sameCell = false)
    }
  }

  test("toree AddJar file same cell") {
    KernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(scalaVersion, sameCell = true)
    }
  }

  test("toree AddJar URL") {
    KernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(scalaVersion, sameCell = false)
    }
  }

  test("toree AddJar URL same cell") {
    KernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(scalaVersion, sameCell = true)
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

  test("compile only") {
    KernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.compileOnly()
    }
  }

}
