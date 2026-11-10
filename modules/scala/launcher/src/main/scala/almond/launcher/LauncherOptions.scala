package almond.launcher

import almond.OutputStyle
import almond.channels.zeromq.ZeromqThreads
import almond.kernel.install.{OptionGroups, Options => InstallOptions}
import almond.launcher.directives.CustomGroup
import caseapp._

import almond.directivehandler.EitherSequence._
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}

// The options also accepted by the kernel are documented in almond.Options. The Markdown
// documentation (second argument of @HelpMessage) of the other ones goes in the options page of
// the website (docs/pages/install-options.md, updated with ./mill docs.updateOptionsPage).

// format: off
final case class LauncherOptions(
  @Group(OptionGroups.installation)
  @HelpMessage("Install the kernel")
    install: Boolean = false,
  @Recurse
    installOptions: InstallOptions = InstallOptions(),
  @Group(OptionGroups.internal)
  @HelpMessage("Log level (one of none, error, warn, info, debug)")
  @ValueDescription("none|error|warn|info|debug")
    log: Option[String] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage("Path to the connection file Jupyter passes to the kernel")
    connectionFile: Option[String] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage("Whether to enable variable inspector")
    variableInspector: Option[Boolean] = None,
  @Group(OptionGroups.toree)
  @HelpMessage("Add experimental support for Toree magics")
    toreeMagics: Option[Boolean] = None,
  @Group(OptionGroups.toree)
  @HelpMessage("Add experimental support for Toree API compatibility")
    toreeApi: Option[Boolean] = None,
  @Group(OptionGroups.toree)
  @HelpMessage("Add experimental support for Toree compatibility (magics and API)")
    toreeCompatibility: Option[Boolean] = None,
  @Group(OptionGroups.scala)
  @HelpMessage("Enable or disable color cell output upon startup (enabled by default, pass --color=false to disable)")
    color: Option[Boolean] = None,
  @Group(OptionGroups.internal)
  @HelpMessage("Send log to a file rather than stderr")
  @ValueDescription("/path/to/log-file")
    logTo: Option[String] = None,
  @Group(OptionGroups.launcher)
  @HelpMessage(
    "Default Scala version of the notebooks, that users can change with a '//> using scala' directive",
    "The default Scala version of the notebooks, that users can change with a `//> using scala` directive."
  )
  @ValueDescription("version")
    scala: Option[String] = None,
  @Group(OptionGroups.dependency)
  @HelpMessage("Extra class path to add upfront in the session - should accept the same format as 'java -cp'")
  @ExtraName("extraCp")
  @ExtraName("extraClasspath")
    extraClassPath: List[String] = Nil,
  @Group(OptionGroups.scala)
  @HelpMessage("File whose code to run right before the session starts (can be repeated)")
    predef: List[String] = Nil,
  @Group(OptionGroups.dependency)
  @HelpMessage("Dependency to add to the user class path before running any user code, like org::name:version (can be repeated)")
  @ExtraName("dep")
    dependency: List[String] = Nil,
  @Group(OptionGroups.installation)
  @HelpMessage("Extra class path to add alongside the kernel JARs - should accept the same format as 'java -cp'")
    extraStartupClassPath: List[String] = Nil,
  @Group(OptionGroups.launcher)
  @HelpMessage(
    "Dependencies to add to the user-facing part of the Almond class path (can be repeated)",
    "Dependencies to add to the user-facing part of the Almond class path. Can be repeated."
  )
    sharedDependencies: List[String] = Nil,
  @Group(OptionGroups.scala)
  @HelpMessage("Enable compile-only mode")
    compileOnly: Option[Boolean] = None,
  @Group(OptionGroups.launcher)
  @HelpMessage(
    "Java option to pass to the kernel, like -Xmx4g (can be repeated)",
    """A Java option to pass to the kernel, like `--java-opt -Xmx4g` (see [Memory](install-advanced.md#memory)).
      |Can be repeated. Notebooks can pass more of them with `//> using javaOpt` directives.""".stripMargin
  )
    javaOpt: List[String] = Nil,
  @Group(OptionGroups.internal)
  @HelpMessage("If false, duplicates stdout/stderr to console, similar to IPKernelApp.quiet")
    quiet: Option[Boolean] = None,
  @Group(OptionGroups.scala)
  @HelpMessage("Whether to silence imports (not printing them back in output)")
    silentImports: Option[Boolean] = None,
  @Group(OptionGroups.scala)
  @HelpMessage("Whether to automatically update the output of var-s upon change (default: true)")
    autoUpdateVars: Option[Boolean] = None,
  @Group(OptionGroups.scala)
  @HelpMessage("Whether to automatically update the output of lazy val-s upon computation (default: true)")
    autoUpdateLazyVals: Option[Boolean] = None,
  @Group(OptionGroups.scala)
  @HelpMessage("How cell results are displayed: default (each value, with its name and type), last (only the last value, with its name and type), or python (like the Python kernel, only the value of the last expression) - can be changed mid-session with '//> using outputStyle default|last|python'")
  @ValueDescription("default|last|python")
    outputStyle: Option[String] = None,
  @HelpMessage("Kept for compatibility, same as --output-style last")
  @Hidden
    lastValueOnly: Option[Boolean] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage("Whether to use a notebook-specific coursier logger")
    useNotebookCoursierLogger: Option[Boolean] = None,
  @Group(OptionGroups.launcher)
  @HelpMessage(
    "Prefix of custom directives, and command handling them, like spark:/path/to/command (can be repeated)",
    """Have a command handle the directives whose key starts with a prefix, like `spark` for
      |`//> using spark.version "3.5.8"`. Use like
      |```bash
      |--custom-directive-group spark:/path/to/handle-spark-directives
      |```
      |Can be repeated. See [Custom directives](usage-directives.md#custom-directives).""".stripMargin
  )
  @ValueDescription("prefix:command")
    customDirectiveGroup: List[String] = Nil,
  @HelpMessage("Time given to the client to accept ZeroMQ messages before handing over the connections to the kernel. Parsed with scala.concurrent.duration.Duration, this accepts things like \"Inf\" or \"5 seconds\"")
  @Hidden
    linger: Option[String] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "If zero-d ports are passed by Jupyter in connection file, bind to random available ports, " +
    "and update the connection file with the actually used ports"
  )
    bindToRandomPorts: Option[Boolean] = None,
  @HelpMessage("Run the actual kernel in the launcher process, via exec, when possible, rather than in a sub-process relaying messages through the launcher (default: true)")
  @Hidden
    exec: Option[Boolean] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage("Maximum size of the ZeroMQ messages accepted by the kernel, like 500m, 2g, or 1048576 (in bytes), or none for no limit (default: 500m). Peers sending larger messages get disconnected.")
  @ValueDescription("size|none")
    maxMessageSize: Option[String] = None,
  @HelpMessage("Class name to use to wrap user code - wrapping classes will be this name with an integer index appended")
  @Hidden
    wrapperName: Option[String] = None,
  @HelpMessage("Package name where user code should be compiled - defaults to ammonite.$sess")
  @Hidden
    pkgName: Option[String] = None,
  @Group(OptionGroups.internal)
  @HelpMessage("Directory to write the class files compiled from cells to")
  @ExtraName("outputDir")
    outputDirectory: Option[String] = None,
  @Group(OptionGroups.internal)
  @HelpMessage("Whether to write the class files compiled from cells to a temporary directory, rather than keeping them in memory only (default: true)")
  @ExtraName("tmpOutputDir")
    tmpOutputDirectory: Option[Boolean] = None,
  @Hidden
    logCode: Option[Boolean] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage("User name to use in the headers of the messages sent by the kernel (default: name of the user running the kernel)")
    username: Option[String] = None,
  @Group(OptionGroups.launcher)
  @HelpMessage("Print the Almond version and exit", "Print the Almond version and exit.")
  @Name("v")
    version: Boolean = false
) {
  // format: on

  def kernelOptions: Seq[String] = {
    val b = new mutable.ListBuffer[String]
    for (value <- log)
      b ++= Seq("--log", value)
    for (value <- variableInspector)
      b ++= Seq(s"--variable-inspector=$value")
    for (value <- toreeMagics)
      b ++= Seq(s"--toree-magics=$value")
    for (value <- toreeApi)
      b ++= Seq(s"--toree-api=$value")
    for (value <- toreeCompatibility)
      b ++= Seq(s"--toree-compatibility=$value")
    for (value <- color)
      b ++= Seq(s"--color=$value")
    for (value <- logTo)
      b ++= Seq("--log-to", value)
    for (value <- extraClassPath)
      b ++= Seq("--extra-class-path", value)
    for (value <- predef)
      b ++= Seq("--predef", value)
    for (value <- dependency)
      b ++= Seq("--dependency", value)
    for (value <- compileOnly)
      b ++= Seq(s"--compile-only=$value")
    for (value <- lastValueOnly)
      b ++= Seq(s"--last-value-only=$value")
    for (value <- outputStyle)
      b ++= Seq(s"--output-style=$value")
    for (value <- silentImports)
      b ++= Seq(s"--silent-imports=$value")
    for (value <- autoUpdateVars)
      b ++= Seq(s"--auto-update-vars=$value")
    for (value <- autoUpdateLazyVals)
      b ++= Seq(s"--auto-update-lazy-vals=$value")
    for (value <- useNotebookCoursierLogger)
      b ++= Seq(s"--use-notebook-coursier-logger=$value")
    for (group <- customDirectiveGroup.map(_.split(":", 2)).collect { case Array(k, _) => k })
      b ++= Seq(s"--launcher-directive-group=$group")
    for (name <- wrapperName)
      b += s"--wrapper-name=$name"
    for (name <- pkgName)
      b += s"--pkg-name=$name"
    for (outputDir <- outputDirectory)
      b += s"--output-directory=$outputDir"
    for (tmpOutputDir <- tmpOutputDirectory)
      b += s"--tmp-output-directory=$tmpOutputDir"
    for (logCode0 <- logCode)
      b += s"--log-code=$logCode0"
    for (value <- username)
      b ++= Seq("--username", value)
    for (value <- maxMessageSize)
      b += s"--max-message-size=$value"
    b.result()
  }

  def quiet0 = quiet.getOrElse(true)

  def outputStyleOrExit(): Option[OutputStyle] =
    outputStyle.map { input =>
      OutputStyle.parse(input) match {
        case Left(err) =>
          System.err.println(s"Error: $err")
          sys.exit(1)
        case Right(style) => style
      }
    }

  def maxMessageSizeOrExit(): Option[Long] =
    maxMessageSize.map(_.trim).filter(_.nonEmpty) match {
      case Some(input) =>
        ZeromqThreads.parseMaxMessageSize(input) match {
          case Left(err) =>
            System.err.println(s"Error: $err")
            sys.exit(1)
          case Right(sizeOpt) => sizeOpt
        }
      case None =>
        Some(ZeromqThreads.defaultMaxMessageSize)
    }

  def customDirectiveGroupsOrExit(): Seq[CustomGroup] = {
    val maybeGroups = customDirectiveGroup
      .map { input =>
        input.split(":", 2) match {
          case Array(prefix, command) => Right(CustomGroup(prefix, command))
          case Array(_) =>
            Left(s"Malformed custom directive group argument, expected 'prefix:command': '$input'")
        }
      }
      .sequence

    maybeGroups match {
      case Left(errors) =>
        for (err <- errors)
          System.err.println(err)
        sys.exit(1)
      case Right(groups) =>
        groups
    }
  }

  lazy val lingerDuration = linger
    .map(_.trim)
    .filter(_.nonEmpty)
    .map(Duration(_))
    .getOrElse(5.seconds)
}

object LauncherOptions {
  implicit lazy val parser: Parser[LauncherOptions] = Parser.derive
  implicit lazy val help: Help[LauncherOptions]     = Help.derive
}
