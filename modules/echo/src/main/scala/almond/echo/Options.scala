package almond.echo

import almond.kernel.install.{Options => InstallOptions}
import caseapp.{HelpMessage, Recurse}

final case class Options(
  connectionFile: Option[String] = None,
  @HelpMessage("Log level (one of none, error, warn, info, or debug)")
  log: String = "warn",
  install: Boolean = false,
  @Recurse
  installOptions: InstallOptions = InstallOptions()
)
