package jupyter
package scala

import kernel.{ Module, KernelInfo, Kernel, BuildInfo }

object EmbeddedScalaModule extends Module {
  private val scalaBinaryVersion = BuildInfo.scalaVersion split '.' take 2 mkString "."

  val kernelId = s"scala-$scalaBinaryVersion-embedded"
  val kernel = EmbeddedScalaKernel
  val kernelInfo = KernelInfo(s"Scala $scalaBinaryVersion (embedded)", "scala", List("snb"))

  def kernels: Map[String, (Kernel, KernelInfo)] =
    Map(kernelId -> (kernel, kernelInfo))
}
