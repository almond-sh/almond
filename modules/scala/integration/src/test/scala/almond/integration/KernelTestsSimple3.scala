package almond.integration

class KernelTestsSimple3 extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Legacy, KernelLauncher.testScalaVersion)

}
