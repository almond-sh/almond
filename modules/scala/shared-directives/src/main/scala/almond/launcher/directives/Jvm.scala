package almond.launcher.directives

import scala.cli.directivehandler._

@DirectiveGroupName("JVM version")
@DirectiveExamples("//> using jvm 11")
@DirectiveExamples("//> using jvm adopt:11")
@DirectiveExamples("//> using jvm graalvm:21")
@DirectiveUsage(
  "//> using jvm _value_",
  "`//> using jvm` _value_"
)
@DirectiveDescription("Use a specific JVM, such as `14`, `adopt:11`, or `graalvm:21`, or `system`")
final case class Jvm(
  jvm: Option[Positioned[String]] = None
) extends HasLauncherParameters {
  def launcherParameters = LauncherParameters(
    jvm = jvm.map(_.value).filter(_.trim.nonEmpty)
  )
}

object Jvm {
  val handler: DirectiveHandler[Jvm] = DirectiveHandler.deriver[Jvm].derive
}
