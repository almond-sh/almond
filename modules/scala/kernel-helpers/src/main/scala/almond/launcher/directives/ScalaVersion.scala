package almond.launcher.directives

import scala.cli.directivehandler._

@DirectiveGroupName("Scala version")
@DirectiveExamples("//> using scala 3.0.2")
@DirectiveExamples("//> using scala 2.13")
@DirectiveExamples("//> using scala 2")
@DirectiveUsage(
  "//> using scala _version_",
  "`//> using scala `_version_"
)
@DirectiveDescription("Set the Scala version")
final case class ScalaVersion(
  scala: Option[String] = None
) extends HasLauncherParameters {
  def launcherParameters = LauncherParameters(
    scala = scala.filter(_.trim.nonEmpty)
  )
}

object ScalaVersion {
  val handler: DirectiveHandler[ScalaVersion] = DirectiveHandler.deriver[ScalaVersion].derive
}
