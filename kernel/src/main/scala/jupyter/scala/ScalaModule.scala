package jupyter
package scala

import kernel.{Module, KernelInfo, Kernel, BuildInfo}

object ScalaModule extends Module {
  private val scalaBinaryVersion = BuildInfo.scalaVersion split '.' take 2 mkString "."

  val kernelId = s"scala-$scalaBinaryVersion"
  val kernel = ScalaKernel
  val kernelInfo = KernelInfo(s"Scala $scalaBinaryVersion", "scala", List("snb"))

  def kernels: Map[String, (Kernel, KernelInfo)] =
    Map(kernelId -> (kernel, kernelInfo))
}
