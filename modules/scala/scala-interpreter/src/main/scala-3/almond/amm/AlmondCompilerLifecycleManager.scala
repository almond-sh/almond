package almond.amm

import java.nio.file.Path

import almond.logger.LoggerContext
import ammonite.compiler.CompilerLifecycleManager
import ammonite.compiler.iface.Preprocessor
import dotty.tools.dotc.util.SourceFile

class AlmondCompilerLifecycleManager(
  rtCacheDir: Option[Path],
  headFrame: => ammonite.util.Frame,
  dependencyCompleteOpt: => Option[String => (Int, Seq[String])],
  classPathWhitelist: Set[Seq[String]],
  initialClassLoader: ClassLoader,
  autoUpdateLazyVals: Boolean,
  autoUpdateVars: Boolean,
  variableInspectorEnabled: () => Boolean,
  logCtx: LoggerContext
) extends CompilerLifecycleManager(
  rtCacheDir,
  headFrame,
  dependencyCompleteOpt,
  classPathWhitelist,
  initialClassLoader
) {

  override def preprocess(fileName: String): Preprocessor = synchronized{
    init()
    new AlmondPreprocessor(
      compiler.initialCtx.fresh.withSource(SourceFile.virtual(fileName, "")),
      autoUpdateLazyVals,
      autoUpdateVars,
      variableInspectorEnabled,
      logCtx
    )
  }

  def preConfigure(): Unit =
    ()
}

object AlmondCompilerLifecycleManager {

  private[almond] def isAtLeast_2_12_7 = true

}
