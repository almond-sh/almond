package almond.internals

import ammonite.compiler.CompilerExtensions._
import ammonite.interp.api.InterpAPI

object ConfigureCompiler {
  def addOptions(interpApi: InterpAPI)(options: Seq[String]): Unit =
    interpApi.preConfigureCompiler { settings =>
      val (success, _) = settings.processArguments(options.toList, true)
      if (!success)
        throw new Exception(s"Failed to add Scalac options ${options.mkString(" ")}")
    }
}
