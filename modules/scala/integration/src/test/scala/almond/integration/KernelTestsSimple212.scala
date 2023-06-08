package almond.integration

class KernelTestsSimple212 extends KernelTestsDefinitions {

  lazy val kernelLauncher =
    new KernelLauncher(KernelLauncher.LauncherType.Legacy, KernelLauncher.testScala212Version)

}
