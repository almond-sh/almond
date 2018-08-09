package almond.echo

import almond.kernel.install.{Options => InstallOptions}
import caseapp.{HelpMessage, Recurse}

final case class Options(
  connectionFile: Option[String] = None,
  @HelpMessage("Enable logging - if enabled, logging goes to a file named scala-kernel.log in the current directory")
    logTo: Option[String] = None,
  install: Boolean = false,
  @Recurse
    installOptions: InstallOptions = InstallOptions()
)
