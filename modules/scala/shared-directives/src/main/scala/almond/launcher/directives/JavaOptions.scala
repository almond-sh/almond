package almond.launcher.directives

import scala.cli.directivehandler._

@DirectiveGroupName("Java options")
@DirectiveExamples("//> using javaOpt -Xmx2g, -Dsomething=a")
@DirectiveUsage(
  "//> using javaOpt _options_",
  "`//> using javaOpt `_options_"
)
@DirectiveDescription("Add Java options which will be passed when running an application.")
final case class JavaOptions(
  @DirectiveName("javaOpt")
  javaOptions: List[Positioned[String]] = Nil
) extends HasLauncherParameters {
  def launcherParameters = LauncherParameters(
    javaOptions = javaOptions.map(_.value)
  )
}

object JavaOptions {
  val handler: DirectiveHandler[JavaOptions] = DirectiveHandler.deriver[JavaOptions].derive
}
