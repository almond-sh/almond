package almond.integration

class KernelTestsTwoStepStartup extends KernelTestsTwoStepStartupDefinitions {

  lazy val kernelLauncher = new KernelLauncher(KernelLauncher.LauncherType.Jvm, "2.13.10")

}
