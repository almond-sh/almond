package almond.echo

import almond.kernel.install.{Options => InstallOptions}
import caseapp.{HelpMessage, Recurse}
import caseapp.core.help.Help
import caseapp.core.parser.Parser
import caseapp.Hidden
import scala.concurrent.duration.{Duration, DurationInt}

// format: off
final case class Options(
  connectionFile: Option[String] = None,
  @HelpMessage("Log level (one of none, error, warn, info, or debug)")
    log: String = "warn",
  install: Boolean = false,
  @Recurse
    installOptions: InstallOptions = InstallOptions(),

  @HelpMessage(
    """Time given to the client to accept ZeroMQ messages before exiting. Parsed with scala.concurrent.duration.Duration, this accepts things like "Inf" or "5 seconds""""
  )
  @Hidden
    linger: Option[String] = None
) {
  // format: on

  lazy val lingerDuration = linger
    .map(_.trim)
    .filter(_.nonEmpty)
    .map(Duration(_))
    .getOrElse(5.seconds)
}

object Options {
  implicit lazy val parser: Parser[Options] = Parser.derive
  implicit lazy val help: Help[Options]     = Help.derive
}
