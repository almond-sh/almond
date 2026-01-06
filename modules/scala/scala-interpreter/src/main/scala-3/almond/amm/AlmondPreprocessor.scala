package almond.amm

import ammonite.compiler.Preprocessor
import ammonite.util.Name
import dotty.tools.dotc.core.Contexts._

class AlmondPreprocessor(
  ctx: Context,
  autoUpdateLazyVals: Boolean,
  autoUpdateVars: Boolean,
  silentImports: Boolean,
  variableInspectorEnabled: () => Boolean,
  logCtx: almond.logger.LoggerContext,
  logCode: Boolean // FIXME Respect that
) extends Preprocessor(ctx, markGeneratedSections = false) {

  // useful when debugging
  // this prints the code after pre-processing, that is the code that is actually passed to scalac for compilation
  private lazy val log = logCtx(getClass)
  override def transform(
    stmts: Seq[String],
    resultIndex: String,
    leadingSpaces: String,
    codeSource: ammonite.util.Util.CodeSource,
    indexedWrapper: Name,
    imports: ammonite.util.Imports,
    printerTemplate: String => String,
    extraCode: String,
    skipEmpty: Boolean,
    markScript: Boolean,
    codeWrapper: ammonite.compiler.iface.CodeWrapper
  ): ammonite.util.Res[ammonite.compiler.iface.Preprocessor.Output] = {
    val res = super.transform(
      stmts,
      resultIndex,
      leadingSpaces,
      codeSource,
      indexedWrapper,
      imports,
      printerTemplate,
      extraCode,
      skipEmpty,
      markScript,
      codeWrapper
    )
    if (logCode)
      res.map { o =>
        val nl = System.lineSeparator()
        log.info(s"Compiling ${indexedWrapper.encoded}.sc$nl---$nl${o.code}$nl---")
      }
    res
  }
}
