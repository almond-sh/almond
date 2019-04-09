package almond.kernel.install

import caseapp.{HelpMessage, Name}

final case class Options(
  @HelpMessage("erase any previously existing kernel with the same id")
  @Name("f")
    force: Boolean = false,
  @HelpMessage("id for this kernel, instead of the default one")
    id: Option[String] = None,
  @HelpMessage("name for this kernel, instead of the default one")
  @Name("name")
  @Name("N")
    displayName: Option[String] = None,
  @HelpMessage("whether to install this kernel globally")
    global: Boolean = false,
  jupyterPath: Option[String] = None,
  @HelpMessage("path to a 64x64 PNG logo for this kernel")
    logo: Option[String] = None,
  @HelpMessage("command to launch this kernel, specified argument per argument, like --arg /foo --arg some-arg")
    arg: List[String] = Nil,
  @HelpMessage("command to launch this kernel, as one block (then split, takes precedence over --arg)")
    command: Option[String] = None,
  @HelpMessage("whether to request frontends to interrupt this kernel via a message")
    interruptViaMessage: Boolean = false,
  @HelpMessage("Whether to copy the kernel launcher in the kernelspec directory (default: false if --arg or --command specified, true else)")
    copyLauncher: Option[Boolean] = None
) {
  def copyLauncher0: Boolean =
    copyLauncher.getOrElse {
      arg.isEmpty && command.isEmpty
    }
}
