package almond.launcher

import almond.OutputStyle
import almond.kernel.install.{Options => InstallOptions}
import almond.launcher.directives.CustomGroup
import caseapp._

import scala.cli.directivehandler.EitherSequence._
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}

// format: off
final case class LauncherOptions(
  install: Boolean = false,
  @Recurse
    installOptions: InstallOptions = InstallOptions(),
  log: Option[String] = None,
  connectionFile: Option[String] = None,
  variableInspector: Option[Boolean] = None,
  toreeMagics: Option[Boolean] = None,
  toreeApi: Option[Boolean] = None,
  toreeCompatibility: Option[Boolean] = None,
  color: Option[Boolean] = None,
  @HelpMessage("Send log to a file rather than stderr")
  @ValueDescription("/path/to/log-file")
    logTo: Option[String] = None,
  scala: Option[String] = None,
  @ExtraName("extraCp")
  @ExtraName("extraClasspath")
    extraClassPath: List[String] = Nil,
  predef: List[String] = Nil,
  @HelpMessage("Dependency to add to the user class path before running any user code, like org::name:version (can be repeated)")
  @ExtraName("dep")
    dependency: List[String] = Nil,
  extraStartupClassPath: List[String] = Nil,
  sharedDependencies: List[String] = Nil,
  compileOnly: Option[Boolean] = None,
  javaOpt: List[String] = Nil,
  quiet: Option[Boolean] = None,
  silentImports: Option[Boolean] = None,
  @HelpMessage("Whether to automatically update the output of var-s upon change (default: true)")
    autoUpdateVars: Option[Boolean] = None,
  @HelpMessage("Whether to automatically update the output of lazy val-s upon computation (default: true)")
    autoUpdateLazyVals: Option[Boolean] = None,
  @HelpMessage("How cell results are displayed: default (each value, with its name and type), last (only the last value, with its name and type), or python (like the Python kernel, only the value of the last expression) - can be changed mid-session with '//> using outputStyle default|last|python'")
  @ValueDescription("default|last|python")
    outputStyle: Option[String] = None,
  @HelpMessage("Kept for compatibility, same as --output-style last")
  @Hidden
    lastValueOnly: Option[Boolean] = None,
  useNotebookCoursierLogger: Option[Boolean] = None,
  customDirectiveGroup: List[String] = Nil,
  @HelpMessage("Time given to the client to accept ZeroMQ messages before handing over the connections to the kernel. Parsed with scala.concurrent.duration.Duration, this accepts things like \"Inf\" or \"5 seconds\"")
  @Hidden
    linger: Option[String] = None,
  @HelpMessage(
    "If zero-d ports are passed by Jupyter in connection file, bind to random available ports, " +
    "and update the connection file with the actually used ports"
  )
    bindToRandomPorts: Option[Boolean] = None,
  @HelpMessage("Class name to use to wrap user code - wrapping classes will be this name with an integer index appended")
  @Hidden
    wrapperName: Option[String] = None,
  @HelpMessage("Package name where user code should be compiled - defaults to ammonite.$sess")
  @Hidden
    pkgName: Option[String] = None,
  @ExtraName("outputDir")
    outputDirectory: Option[String] = None,
  @ExtraName("tmpOutputDir")
    tmpOutputDirectory: Option[Boolean] = None,
  @Hidden
    logCode: Option[Boolean] = None,
  @HelpMessage("User name to use in the headers of the messages sent by the kernel (default: name of the user running the kernel)")
    username: Option[String] = None,
  @HelpMessage("Print the Almond version and exit")
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
