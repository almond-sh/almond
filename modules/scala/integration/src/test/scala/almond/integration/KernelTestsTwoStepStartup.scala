package almond.integration

class KernelTestsTwoStepStartup extends KernelTestsTwoStepStartupDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Jvm, KernelLauncher.testScala213Version)

  override def mightRetry = true

}
