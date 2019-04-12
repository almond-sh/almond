package almond.internals

import almond.interpreter._
import almond.logger.LoggerContext
import ammonite.runtime.Frame
import ammonite.util.Ref

final class ScalaInterpreterInspections(
  logCtx: LoggerContext,
  metabrowse: Boolean,
  metabrowseHost: String,
  metabrowsePort: Int,
  pressy: => scala.tools.nsc.interactive.Global,
  frames: Ref[List[Frame]]
) {
  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    None
  def shutdown(): Unit = ()
}
