package almond.integration

import almond.testkit.Dsl._

abstract class KernelTestsDefinitions(
  scalaVersion: String,
  isTwoStepStartup: Boolean
) extends munit.FunSuite {

  val kernelLauncher = new KernelLauncher(isTwoStepStartup = isTwoStepStartup, scalaVersion)

  test("jvm-repr") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.jvmRepr()
    }
  }

  test("updatable display") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.updatableDisplay()
    }
  }

  test("auto-update Future results upon completion") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.autoUpdateFutureUponCompletion(scalaVersion)
    }
  }

  test("auto-update Future results in background upon completion") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.autoUpdateFutureInBackgroundUponCompletion(scalaVersion)
    }
  }

  test("toree AddJar file") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(scalaVersion, sameCell = false)
    }
  }

  test("toree AddJar file same cell") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(scalaVersion, sameCell = true)
    }
  }

  test("toree AddJar URL") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(scalaVersion, sameCell = false)
    }
  }

  test("toree AddJar URL same cell") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(scalaVersion, sameCell = true)
    }
  }

  test("toree AddJar custom protocol") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarCustomProtocol(scalaVersion)
    }
  }

  test("toree custom cell magic") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeCustomCellMagic()
    }
  }

  test("compile only") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.compileOnly()
    }
  }

  if (scalaVersion.startsWith("2."))
    test("extra class path") {
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.extraCp(scalaVersion)
      }
    }

  if (scalaVersion.startsWith("2."))
    test("inspections") {
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.inspections(scalaVersion)
      }
    }

}
