package almond.launcher.directives

import scala.cli.directivehandler.{DirectiveHandler, DirectiveHandlers}

final case class LauncherParameters(
  jvm: Option[String] = None,
  javaOptions: Seq[String] = Nil,
  scala: Option[String] = None
) {
  def +(other: LauncherParameters): LauncherParameters =
    LauncherParameters(
      jvm.orElse(other.jvm),
      javaOptions ++ other.javaOptions,
      scala.orElse(other.scala)
    )
}

object LauncherParameters {

  val handlers = DirectiveHandlers(
    Seq[DirectiveHandler[HasLauncherParameters]](
      JavaOptions.handler,
      Jvm.handler,
      ScalaVersion.handler
    )
  )

}
