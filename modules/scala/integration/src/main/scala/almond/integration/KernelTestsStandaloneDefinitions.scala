package almond.integration

import almond.testkit.Dsl._

/** Basic tests of kernels started from standalone launchers, that embed all the kernel JARs rather
  * than pointing at the coursier cache
  */
abstract class KernelTestsStandaloneDefinitions extends AlmondFunSuite {

  def scalaVersion: String

  lazy val kernelLauncher: KernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Legacy, scalaVersion) {
      override def standaloneLauncher = true
    }

  test0("simple") { implicit forceVerbose =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute("val n = 2", "n: Int = 2")
        execute("n + 1", "res2: Int = 3")
        execute(
          """def double(x: Int) = 2 * x
            |double(n)""".stripMargin,
          """defined function double
            |res3_1: Int = 4""".stripMargin
        )
      }
    }
  }

  test0("jvm-repr") { implicit forceVerbose =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.jvmRepr()
    }
  }

  test0("compilation error") { implicit forceVerbose =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.compilationError(kernelLauncher.defaultScalaVersion)
    }
  }

  // Scala 2 inspections rely on mtags, whose recent versions need Java 17
  if (
    !kernelLauncher.defaultScalaVersion.startsWith("2.") ||
    kernelLauncher.kernelJavaMajorVersion >= 17
  )
    test0("inspections") { implicit forceVerbose =>
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        // reads docs from the source JARs embedded in the launcher
        almond.integration.Tests.inspections(kernelLauncher.defaultScalaVersion)
      }
    }

  test0("add dependency") { implicit forceVerbose =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.addDependency(kernelLauncher.defaultScalaVersion)
    }
  }

}
