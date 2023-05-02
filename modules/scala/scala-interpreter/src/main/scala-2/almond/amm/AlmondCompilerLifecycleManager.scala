package almond.amm

import java.nio.file.Path

import almond.logger.LoggerContext
import ammonite.compiler.CompilerLifecycleManager
import ammonite.compiler.iface.Preprocessor

class AlmondCompilerLifecycleManager(
  rtCacheDir: Option[Path],
  headFrame: => ammonite.util.Frame,
  dependencyCompleteOpt: => Option[String => (Int, Seq[String])],
  classPathWhitelist: Set[Seq[String]],
  initialClassLoader: ClassLoader,
  autoUpdateLazyVals: Boolean,
  autoUpdateVars: Boolean,
  variableInspectorEnabled: () => Boolean,
  outputDir: Option[Path],
  logCtx: LoggerContext
) extends CompilerLifecycleManager(
      rtCacheDir,
      headFrame,
      dependencyCompleteOpt,
      classPathWhitelist,
      initialClassLoader,
      outputDir
    ) {
  override def preprocess(fileName: String): Preprocessor =
    synchronized {
      if (compiler == null) init(force = true)
      // parse method that needs to be put back in Ammonite's public API
      val m = compiler.getClass.getMethod(
        "$anonfun$preprocessor$2",
        compiler.getClass,
        classOf[String],
        classOf[String]
      )
      new AlmondPreprocessor(
        m.invoke(null, compiler, fileName, _)
          .asInstanceOf[Either[String, Seq[scala.tools.nsc.Global#Tree]]],
        autoUpdateLazyVals,
        autoUpdateVars,
        variableInspectorEnabled,
        logCtx
      )
    }

  def preConfigure(): Unit =
    preConfigureCompiler(_.processArguments(Nil, processAll = true))
}

object AlmondCompilerLifecycleManager {

  private[almond] val isAtLeast_2_12_7 = {
    val v = scala.util.Properties.versionNumberString
    !v.startsWith("2.11.") && (!v.startsWith("2.12.") || {
      v.stripPrefix("2.12.").takeWhile(_.isDigit).toInt >= 7
    })
  }

}
