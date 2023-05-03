package almond.internals

import almond.interpreter._
import almond.logger.LoggerContext
import ammonite.util.{Frame, Ref}

final class ScalaInterpreterInspections(
  logCtx: LoggerContext,
  metabrowse: Boolean,
  metabrowseHost: String,
  metabrowsePort: Int,
  scalaVersion: String,
  compilerManager: => ammonite.compiler.CompilerLifecycleManager,
  frames: => List[Frame]
) {
  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    None
  def shutdown(): Unit = ()
}
