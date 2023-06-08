package almond.integration

class KernelTestsTwoStepStartup213 extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Jvm, KernelLauncher.testScala213Version)

  override def mightRetry = true

}
