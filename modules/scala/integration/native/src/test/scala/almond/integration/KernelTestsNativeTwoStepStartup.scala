package almond.integration

class KernelTestsNativeTwoStepStartup extends KernelTestsTwoStepStartupDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Native, KernelLauncher.testScala213Version)

  override def mightRetry = true

}
