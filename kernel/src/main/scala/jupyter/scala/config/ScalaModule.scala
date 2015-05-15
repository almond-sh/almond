package jupyter.scala.config

import jupyter.kernel.config.Module
import jupyter.kernel.interpreter.InterpreterKernel
import jupyter.kernel.KernelInfo
import jupyter.scala.ScalaInterpreter

import scalaz.\/

object ScalaModule extends Module {
  val scalaBinaryVersion = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  val kernelId = s"scala${scalaBinaryVersion.filterNot(_ == '.')}"
  val kernel = new InterpreterKernel {
    def apply() = \/.fromTryCatchNonFatal(ScalaInterpreter())
  }
  val kernelInfo = KernelInfo(s"Scala $scalaBinaryVersion", kernelId)

  def kernels = Map(
    kernelId -> (kernel, kernelInfo)
  )
}
