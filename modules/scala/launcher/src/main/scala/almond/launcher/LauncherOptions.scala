package almond.launcher

import almond.kernel.install.{Options => InstallOptions}
import caseapp._

import scala.collection.mutable

// format: off
final case class LauncherOptions(
  install: Boolean = false,
  @Recurse
    installOptions: InstallOptions = InstallOptions(),
  log: Option[String] = None,
  connectionFile: Option[String] = None,
  variableInspector: Option[Boolean] = None,
  toreeMagics: Option[Boolean] = None,
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
  useNotebookCoursierLogger: Option[Boolean] = None
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
    b.result()
  }

  def quiet0 = quiet.getOrElse(true)
}

object LauncherOptions {
  implicit lazy val parser: Parser[LauncherOptions] = Parser.derive
  implicit lazy val help: Help[LauncherOptions]     = Help.derive
}
