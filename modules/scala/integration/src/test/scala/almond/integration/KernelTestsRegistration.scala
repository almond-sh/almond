package almond.integration

import almond.testkit.Dsl._

/** Starts kernels with a registration file rather than a connection file (kernel startup handshake)
  */
class KernelTestsRegistration extends AlmondFunSuite {

  override def mightRetry = true

  private def registrationLauncher(launcherType: KernelLauncher.LauncherType): KernelLauncher =
    new KernelLauncher(launcherType, KernelLauncher.testScalaVersion) {
      override def kernelUseRegistrationFile = true
    }

  private def check(launcher: KernelLauncher)(implicit
    forceVerbose: AlmondFunSuite.ForceVerbose
  ): Unit =
    launcher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withSession() { implicit session =>
        execute("val n = 2", "n: Int = 2")
        execute("n + 1", "res2: Int = 3")
      }
    }

  test0("kernel") { implicit forceVerbose =>
    check(registrationLauncher(KernelLauncher.LauncherType.Legacy))
  }

  test0("two-step startup") { implicit forceVerbose =>
    check(registrationLauncher(KernelLauncher.LauncherType.Jvm))
  }

}
