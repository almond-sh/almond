package almond.launcher.directives

import scala.cli.directivehandler._

@DirectiveGroupName("Java command")
@DirectiveExamples("//> using javaCmd \"java\" \"-Dfoo=thing\"")
@DirectiveUsage(
  "//> using javaCmd _args_",
  "`//> using javaCmd` _args_"
)
@DirectiveDescription("Specify a java command to run the Scala version-specific kernel")
final case class JavaCommand(
  javaCmd: Option[List[String]] = None
) extends HasLauncherParameters {
  def launcherParameters = LauncherParameters(
    javaCmd = javaCmd.filter(_.exists(_.nonEmpty))
  )
}

object JavaCommand {
  val handler: DirectiveHandler[JavaCommand] = DirectiveHandler.deriver[JavaCommand].derive
}
