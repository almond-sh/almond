package almond.integration

import almond.testkit.Dsl._

abstract class KernelTestsDefinitions extends AlmondFunSuite {

  def kernelLauncher: KernelLauncher

  override def mightRetry = true

  lazy val javaMajorVersion = {
    val versionString =
      sys.props.getOrElse("java.version", sys.error("java.version property not set"))
        .stripPrefix("1.") // for Java 8 and below
    versionString.takeWhile(_ != '.').toInt
  }

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
      almond.integration.Tests.autoUpdateFutureUponCompletion(kernelLauncher.defaultScalaVersion)
    }
  }

  test("auto-update Future results in background upon completion") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.autoUpdateFutureInBackgroundUponCompletion(
        kernelLauncher.defaultScalaVersion
      )
    }
  }

  test("toree AddJar file") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(kernelLauncher.defaultScalaVersion, sameCell = false)
    }
  }

  test("toree AddJar file same cell") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(kernelLauncher.defaultScalaVersion, sameCell = true)
    }
  }

  test("toree AddJar URL") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(kernelLauncher.defaultScalaVersion, sameCell = false)
    }
  }

  test("toree AddJar URL same cell") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(kernelLauncher.defaultScalaVersion, sameCell = true)
    }
  }

  test("toree Html") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeHtml()
    }
  }

  test("toree AddJar custom protocol") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarCustomProtocol(kernelLauncher.defaultScalaVersion)
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

  if (kernelLauncher.defaultScalaVersion.startsWith("2."))
    test("extra class path") {
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.extraCp(kernelLauncher.defaultScalaVersion)
      }
    }

  if (kernelLauncher.defaultScalaVersion.startsWith("2.") && javaMajorVersion >= 11)
    test("inspections") {
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.inspections(kernelLauncher.defaultScalaVersion)
      }
    }

  test("compilation error") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.compilationError(kernelLauncher.defaultScalaVersion)
    }
  }

  test("add dependency") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.addDependency(kernelLauncher.defaultScalaVersion)
    }
  }

  test("add repository") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.addRepository(kernelLauncher.defaultScalaVersion)
    }
  }

  test("add scalac option") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.addScalacOption(kernelLauncher.defaultScalaVersion)
    }
  }

  test("completion") {
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.completion(kernelLauncher.defaultScalaVersion)
    }
  }

}
