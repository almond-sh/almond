package almond.echo

import almond.kernel.install.{Options => InstallOptions}
import caseapp.{HelpMessage, Recurse}
import caseapp.core.help.Help
import caseapp.core.parser.Parser

final case class Options(
  connectionFile: Option[String] = None,
  @HelpMessage("Log level (one of none, error, warn, info, or debug)")
  log: String = "warn",
  install: Boolean = false,
  @Recurse
  installOptions: InstallOptions = InstallOptions()
)

object Options {
  implicit lazy val parser: Parser[Options] = Parser.derive
  implicit lazy val help: Help[Options]     = Help.derive
}
