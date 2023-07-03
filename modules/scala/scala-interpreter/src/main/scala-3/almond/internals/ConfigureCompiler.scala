package almond.internals

import ammonite.compiler.CompilerExtensions._
import ammonite.compiler.CompilerLifecycleManager
import ammonite.interp.api.InterpAPI
import dotty.tools.dotc.ScalacCommand

object ConfigureCompiler {
  def addOptions(interpApi: InterpAPI)(options: Seq[String]): Unit =
    interpApi.preConfigureCompiler { ctx =>
      val summary =
        ScalacCommand.distill(options.toArray, ctx.settings)(ctx.settingsState)(using ctx)
      ctx.setSettings(summary.sstate)
    }
}
