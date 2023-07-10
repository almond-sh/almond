package almond.integration

class KernelTestsTwoStepStartup3 extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Jvm, KernelLauncher.testScalaVersion)

}
