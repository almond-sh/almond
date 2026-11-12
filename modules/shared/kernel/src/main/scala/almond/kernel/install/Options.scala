package almond.kernel.install

import caseapp.{Group, HelpMessage, Name, ValueDescription}

// The second argument of @HelpMessage is the Markdown documentation of the options, in the
// options page of the website (docs/pages/install-options.md, updated with
// ./mill docs.updateOptionsPage), the first one being shown in the --help output

// format: off
final case class Options(
  @Group(OptionGroups.installation)
  @HelpMessage(
    "erase any previously existing kernel with the same id",
    "Erase a previously installed kernel with the same ID."
  )
  @Name("f")
    force: Boolean = false,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "id for this kernel, instead of the default one",
    "Set the kernel id, like `--id custom-id`. This id is used to construct the\n" +
      "[path where this kernel's spec is going to be installed](https://jupyter-client.readthedocs.io/en/stable/kernels.html#kernel-specs). Use like\n" +
      "```bash\n" +
      "--id scala212\n" +
      "```\n" +
      "\n" +
      "Default: `scala`"
  )
    id: Option[String] = None,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "name for this kernel, instead of the default one",
    "Set the displayed name for this kernel. This is the name that appears in the Jupyter\n" +
      "menus, etc. Use like\n" +
      "```bash\n" +
      "--display-name \"Scala (2.12)\"\n" +
      "```\n" +
      "\n" +
      "Default: `Scala`"
  )
  @Name("name")
  @Name("N")
    displayName: Option[String] = None,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "whether to install this kernel globally",
    "Whether to install this kernel system-wide or not.\n" +
      "\n" +
      "System-wide kernels get installed under `/usr/local/share/jupyter/kernels` (Linux and OS X). User-wide ones\n" +
      "(default) under `~/.local/share/jupyter/kernels` (Linux) or `~/Library/Jupyter/kernels` (Mac).\n" +
      "\n" +
      "On Windows, like Jupyter itself since jupyter_core 5.8, system-wide kernels get installed under\n" +
      "the `share\\jupyter\\kernels` directory of the Python environment of Jupyter, like\n" +
      "`C:\\Python313\\share\\jupyter\\kernels`. Almond infers that environment from `CONDA_PREFIX` or from\n" +
      "the location of the `jupyter` command (see `--jupyter-command`). `%PROGRAMDATA%\\jupyter\\kernels`\n" +
      "is only used if the `JUPYTER_USE_PROGRAMDATA` environment variable is set, as non-admin users\n" +
      "can create directories under `%PROGRAMDATA%`."
  )
    global: Boolean = false,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "Path to your Jupyter kernels directory, e.g. /opt/conda/share/jupyter/kernels",
    "Path to your Jupyter kernels directory, like `/opt/conda/share/jupyter/kernels`."
  )
    jupyterPath: Option[String] = None,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "jupyter command to ask for the directories Jupyter relies on, either a name to look for in PATH or a path (default: jupyter, pass an empty value not to run it)",
    "The `jupyter` command that Almond runs to find the directories Jupyter looks for kernels in,\n" +
      "either a name to look for in the `PATH` or a path (default: `jupyter`). Pass an empty value not\n" +
      "to run it. See [Where the kernel gets installed](install-advanced.md#where-the-kernel-gets-installed)."
  )
    jupyterCommand: Option[String] = None,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "Fail if the directories Jupyter relies on can't be obtained from the jupyter command, rather than falling back to default Jupyter directories",
    "Make the installation fail if the `jupyter` command can't be run, rather than falling back to\n" +
      "the default Jupyter directories."
  )
    requireJupyter: Boolean = false,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "path to a 64x64 PNG logo for this kernel",
    "Path to a 64 x 64 PNG file. Set to an empty string to remove the default logo, like `--logo \"\"`. (Defaults to a scala logo embedded with the kernel.)"
  )
    logo: Option[String] = None,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "command to launch this kernel, specified argument per argument, like --arg /foo --arg some-arg",
    "Customize the command to spawn the kernel, argument per argument.\n" +
      "\n" +
      "By default, it is assumed coursier or a coursier launcher\n" +
      "is involved during the installation. This allows to get the command that spawns the kernel for install. (Actual executable is located\n" +
      "via the `coursier.mainJar` Java property, and its arguments via `coursier.main.arg-0`,  `coursier.main.arg-1`, ... Java properties.) This command is then used to later start actual kernels. It runs the\n" +
      "JVM the installation runs on, via the absolute path of its `java` command, and the kernel spec\n" +
      "sets `JAVA_HOME` to that JVM too (unless `JAVA_HOME` is passed via [`--env`](#--env)).\n" +
      "\n" +
      "To override that behavior, pass `--arg`s or `--command` options. `--arg` allows to pass each argument individually, like\n" +
      "```bash\n" +
      "--arg \"/path to/executable\" --arg first-arg --arg second-arg\n" +
      "```"
  )
    arg: List[String] = Nil,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "command to launch this kernel, as one block (then split, takes precedence over --arg)",
    "Customize the command to spawn the kernel, like `--arg` does, but passing the command as a whole, like\n" +
      "```bash\n" +
      "--command \"/path/to/executable first-arg second-arg\"\n" +
      "```\n" +
      "which is latter split at space chars. Takes precedence over `--arg`."
  )
    command: Option[String] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "whether to request frontends to interrupt this kernel via a message",
    "Whether to ask front-ends to interrupt the kernel by sending it\n" +
      "[an interrupt message](https://jupyter-client.readthedocs.io/en/stable/messaging.html#kernel-interrupt),\n" +
      "rather than a SIGINT signal (default)."
  )
    interruptViaMessage: Boolean = false,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "Whether to copy the kernel launcher in the kernelspec directory (default: false if --arg or --command specified, true else)",
    "Whether to copy the kernel launcher in the kernel installation directory.\n" +
      "\n" +
      "Default: true if `--arg` and `--command` aren't specified, false else."
  )
    copyLauncher: Option[Boolean] = None,
  @Group(OptionGroups.installation)
  @HelpMessage(
    "Environment variables to set in the kernel spec, that Jupyter passes to the kernel when starting it, as name=value, or just name to use the value of that variable at install time",
    "Set an environment variable in the kernel spec, that Jupyter sets when starting the kernel.\n" +
      "Can be repeated. Use like\n" +
      "```bash\n" +
      "--env SPARK_HOME=/opt/spark --env COURSIER_REPOSITORIES=\"ivy2Local|https://artifacts.company.com/maven\"\n" +
      "```\n" +
      "\n" +
      "Pass just a name, without `=value`, to use the value that variable has in the environment of the\n" +
      "installation command. This writes its current value in the kernel spec, the kernel doesn't read it later on.\n" +
      "Installation fails if that variable isn't set.\n" +
      "```bash\n" +
      "export COURSIER_REPOSITORIES=\"ivy2Local|https://artifacts.company.com/maven\"\n" +
      "--env COURSIER_REPOSITORIES\n" +
      "```"
  )
  @ValueDescription("name=value|name")
    env: List[String] = Nil
) {
  // format: on
  def copyLauncher0: Boolean =
    copyLauncher.getOrElse {
      arg.isEmpty && command.isEmpty
    }
  def envMap(): Map[String, String] =
    envMap(sys.env)
  def envMap(currentEnv: Map[String, String]): Map[String, String] =
    env
      .map { input =>
        input.split("=", 2) match {
          case Array(k, v) if k.nonEmpty => (k, v)
          case Array(k) if k.nonEmpty =>
            val v = currentEnv.getOrElse(
              k,
              sys.error(
                s"Environment variable $k passed via --env isn't set (pass '$k=value' to specify its value)"
              )
            )
            (k, v)
          case _ =>
            sys.error(s"Malformed --env value '$input' (expected 'name=value' or 'name')")
        }
      }
      .toMap
}
