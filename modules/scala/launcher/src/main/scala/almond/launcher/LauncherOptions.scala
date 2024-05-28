package almond.launcher

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
  extraStartupClassPath: List[String] = Nil,
  sharedDependencies: List[String] = Nil,
  compileOnly: Option[Boolean] = None,
  javaOpt: List[String] = Nil,
  quiet: Option[Boolean] = None,
  silentImports: Option[Boolean] = None,
  useNotebookCoursierLogger: Option[Boolean] = None,
  customDirectiveGroup: List[String] = Nil,
  @HelpMessage("Time given to the client to accept ZeroMQ messages before handing over the connections to the kernel. Parsed with scala.concurrent.duration.Duration, this accepts things like \"Inf\" or \"5 seconds\"")
  @Hidden
    linger: Option[String] = None
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
    for (value <- compileOnly)
      b ++= Seq(s"--compile-only=$value")
    for (value <- silentImports)
      b ++= Seq(s"--silent-imports=$value")
    for (value <- useNotebookCoursierLogger)
      b ++= Seq(s"--use-notebook-coursier-logger=$value")
    for (group <- customDirectiveGroup.map(_.split(":", 2)).collect { case Array(k, _) => k })
      b ++= Seq(s"--launcher-directive-group=$group")
    b.result()
  }

  def quiet0 = quiet.getOrElse(true)

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
