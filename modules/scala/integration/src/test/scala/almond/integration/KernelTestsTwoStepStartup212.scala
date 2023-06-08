package almond.integration

class KernelTestsTwoStepStartup212 extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Jvm, KernelLauncher.testScala212Version)

}
