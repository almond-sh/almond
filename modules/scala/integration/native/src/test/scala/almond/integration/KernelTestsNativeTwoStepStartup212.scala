package almond.integration

class KernelTestsNativeTwoStepStartup212
    extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Native, KernelLauncher.testScala212Version)

  override def mightRetry = true

}
