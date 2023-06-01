package almond.integration

class KernelTestsTwoStepStartup213 extends KernelTestsDefinitions {

  lazy val kernelLauncher = new KernelLauncher(KernelLauncher.LauncherType.Jvm, "2.13.10")

}
