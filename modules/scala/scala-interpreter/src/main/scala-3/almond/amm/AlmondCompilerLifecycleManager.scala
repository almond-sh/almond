package almond.amm

import java.nio.file.Path

import almond.logger.LoggerContext
import ammonite.compiler.CompilerLifecycleManager
import ammonite.compiler.iface
import dotty.tools.dotc.util.SourceFile

class AlmondCompilerLifecycleManager(
  rtCacheDir: Option[Path],
  headFrame: => ammonite.util.Frame,
  dependencyCompleteOpt: => Option[String => (Int, Seq[String])],
  classPathWhitelist: Set[Seq[String]],
  initialClassLoader: ClassLoader,
  autoUpdateLazyVals: Boolean,
  autoUpdateVars: Boolean,
  silentImports: Boolean,
  variableInspectorEnabled: () => Boolean,
  outputDir: Option[Path],
  initialSettings: Seq[String],
  logCtx: LoggerContext,
  logCode: Boolean
) extends CompilerLifecycleManager(
      rtCacheDir,
      headFrame,
      dependencyCompleteOpt,
      classPathWhitelist,
      initialClassLoader,
      outputDir,
      initialSettings ++ AlmondCompilerLifecycleManager.extraInitialSettings
    ) {

  override def preprocess(fileName: String): iface.Preprocessor = synchronized {
    init()
    new AlmondPreprocessor(
      compiler.initialCtx.fresh.withSource(SourceFile.virtual(fileName, "")),
      autoUpdateLazyVals,
      autoUpdateVars,
      silentImports,
      variableInspectorEnabled,
      logCtx,
      logCode
    )
  }
}

object AlmondCompilerLifecycleManager {

  private[almond] def isAtLeast_2_12_7 = true

  /** Settings silencing warnings the Ammonite-generated wrapper code triggers */
  private[almond] lazy val extraInitialSettings: Seq[String] = {
    val isAtLeast_3_9 =
      dotty.tools.dotc.config.Properties.versionNumberString.split("[.-]").take(2) match {
        case Array(major, minor) =>
          major.toIntOption.exists(_ > 3) ||
          (major == "3" && minor.toIntOption.exists(_ >= 9))
        case _ => false
      }
    // Scala 3.9 warns (E230) about identifiers containing `$`, which the code wrappers
    // use (`$sess`, `$main`, ...), so every cell would print those warnings.
    if (isAtLeast_3_9) Seq("-Wconf:id=E230:s")
    else Nil
  }

  def closeCompiler(compiler: iface.Compiler): Unit =
    compiler match {
      case c: ammonite.compiler.Compiler =>
      // No resources to be disposed of in Scala 3 compiler?
      case _ =>
        sys.error(s"Unrecognized compiler instance type: $compiler (${compiler.getClass})")
    }

}
