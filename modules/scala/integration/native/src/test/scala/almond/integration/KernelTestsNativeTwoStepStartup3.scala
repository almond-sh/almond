package almond.integration

class KernelTestsNativeTwoStepStartup3
    extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Native, KernelLauncher.testScalaVersion)

  override def mightRetry = true

}
