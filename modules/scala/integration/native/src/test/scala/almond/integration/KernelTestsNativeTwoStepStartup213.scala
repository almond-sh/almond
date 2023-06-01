package almond.integration

class KernelTestsNativeTwoStepStartup213
    extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Native, KernelLauncher.testScala213Version)

  override def mightRetry = true

}
