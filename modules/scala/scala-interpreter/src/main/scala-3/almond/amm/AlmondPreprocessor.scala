package almond.amm

import ammonite.compiler.Preprocessor
import dotty.tools.dotc.core.Contexts._

class AlmondPreprocessor(
  ctx: Context,
  autoUpdateLazyVals: Boolean,
  autoUpdateVars: Boolean,
  variableInspectorEnabled: () => Boolean,
  logCtx: almond.logger.LoggerContext
) extends Preprocessor(ctx, markGeneratedSections = false) {

}
