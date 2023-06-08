package almond.integration

class KernelTestsSimple213 extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Legacy, KernelLauncher.testScala213Version)

}
