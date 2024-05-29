package almond

import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import almond.api.Properties
import almond.channels.{Channel, Message => RawMessage}
import almond.directives.KernelOptions
import almond.kernel.MessageFile
import almond.kernel.install.{Options => InstallOptions}
import almond.protocol.KernelInfo
import caseapp._
import caseapp.core.Scala3Helpers._
import caseapp.core.help.Help
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import coursierapi.{Dependency, Module}
import coursier.parse.{DependencyParser, ModuleParser}

import scala.collection.compat._
import scala.concurrent.duration.{Duration, DurationInt}
import scala.jdk.CollectionConverters._

// format: off
@ProgName("almond")
final case class Options(
  install: Boolean = false,
  @Recurse
    installOptions: InstallOptions = InstallOptions(),
  extraRepository: List[String] = Nil,
  banner: Option[String] = None,
  link: List[String] = Nil,
  predefCode: String = "",
  predef: List[String] = Nil,
  autoDependency: List[String] = Nil,
  autoVersion: List[String] = Nil,
  defaultAutoDependencies: Boolean = true,
  defaultAutoVersions: Boolean = true,
  @HelpMessage("Default almond-spark version to load when Spark is loaded as a library")
    defaultAlmondSparkVersion: Option[String] = None,
  @HelpMessage("Default almond-scalapy version to load when ScalaPy is loaded as a library")
    defaultAlmondScalapyVersion: Option[String] = None,
  @HelpMessage("Force Maven properties during dependency resolution")
    forceProperty: List[String] = Nil,
  @HelpMessage("Enable Maven profile (start with ! to disable)")
    profile: List[String] = Nil,
  @HelpMessage("Log level (one of none, error, warn, info, debug)")
    log: Option[String] = None,
  @HelpMessage("Send log to a file rather than stderr")
  @ValueDescription("/path/to/log-file")
    logTo: Option[String] = None,
  connectionFile: Option[String] = None,
  // For class loader isolation, the user code is loaded from the classloader of the api module.
  // If the right -i / -I options are passed to coursier bootstrap when generating a launcher, that loader
  // only sees the api module and its dependencies, rather than the full classpath of almond.
  @HelpMessage("Use class loader that loaded the api module rather than the context class loader")
    specificLoader: Boolean = true,
  @HelpMessage(
    "Start a metabrowse server for go to source navigation (linked from Jupyter inspections, server is started upon first inspection)"
  )
    metabrowse: Boolean = false,
  @HelpMessage("Trap what user code sends to stdout and stderr")
    trapOutput: Boolean = false,
  @HelpMessage("If false, duplicates stdout/stderr to console, similar to IPKernelApp.quiet")
    quiet: Boolean = true,
  @HelpMessage("Disable ammonite compilation cache")
    disableCache: Boolean = false,
  @HelpMessage("Whether to automatically update lazy val-s upon computation")
    autoUpdateLazyVals: Boolean = true,
  @HelpMessage("Whether to automatically update var-s upon change")
    autoUpdateVars: Boolean = true,
  @HelpMessage("Whether to silence imports (not printing them back in output)")
    silentImports: Boolean = false,
  @HelpMessage("Whether to use a notebook-specific coursier logger")
    useNotebookCoursierLogger: Boolean = false,
  @HelpMessage("Whether to enable variable inspector")
    variableInspector: Option[Boolean] = None,
  @HelpMessage("Whether to process format requests with scalafmt")
    scalafmt: Boolean = true,
  @HelpMessage(
    "Whether to use 'Thread.interrupt' method or deprecated 'Thread.stop' method (default) when interrupting kernel."
  )
    useThreadInterrupt: Boolean = false,

  @ExtraName("outputDir")
    outputDirectory: Option[String] = None,
  @ExtraName("tmpOutputDir")
    tmpOutputDirectory: Option[Boolean] = None,

  @HelpMessage("Add experimental support for Toree magics")
    toreeMagics: Option[Boolean] = None,
  @HelpMessage("Add experimental support for Toree API compatibility")
    toreeApi: Option[Boolean] = None,
  @HelpMessage("Add experimental support for Toree compatibility (magics and API)")
    toreeCompatibility: Option[Boolean] = None,

  @HelpMessage("Enable or disable color cell output upon startup (enabled by default, pass --color=false to disable)")
    color: Boolean = true,

  @HelpMessage("Enable compile-only mode")
    compileOnly: Boolean = false,

  @HelpMessage("Extra class path to add upfront in the session - should accept the same format as 'java -cp', see https://github.com/coursier/class-path-util for more details")
  @ExtraName("extraCp")
  @ExtraName("extraClasspath")
    extraClassPath: List[String] = Nil,

  @HelpMessage("Extra class path to add alongside the kernel JAR upon installation - should accept the same format as 'java -cp', see https://github.com/coursier/class-path-util for more details - only taken into account when --install is specified")
  @ExtraName("extraStartupClasspath")
    extraStartupClassPath: List[String] = Nil,

  @HelpMessage("JSON file containing messages to handle before those originating from the ZeroMQ sockets")
  @Hidden
    leftoverMessages: Option[String] = None,

  @HelpMessage("Number of cells run before starting this kernel")
  @Hidden
    initialCellCount: Option[Int] = None,

  @HelpMessage("Do not send execute_input message for incoming messages with the passed ids")
  @Hidden
    noExecuteInputFor: List[String] = Nil,

  @HelpMessage("Path of JSON file with using directives kernel options")
  @Hidden
    kernelOptions: Option[String] = None,

  @HelpMessage("Do warn users about launcher directives for incoming messages with the passed ids")
  @Hidden
    ignoreLauncherDirectivesIn: List[String] = Nil,

  @HelpMessage("Pass launcher directive groups with this option. These directives will be either ignored (see --ignore-launcher-directives-in), or trigger an unused directive warning")
  @Hidden
    launcherDirectiveGroup: List[String] = Nil,

  @HelpMessage("""Time given to the client to accept ZeroMQ messages before exiting. Parsed with scala.concurrent.duration.Duration, this accepts things like "Inf" or "5 seconds"""")
  @Hidden
    linger: Option[String] = None
) {
  // format: on

  private lazy val sbv = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  private lazy val ammSparkVersion = defaultAlmondSparkVersion
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse(Properties.ammoniteSparkVersion)
  private lazy val almondScalapyVersion = defaultAlmondScalapyVersion
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse(Properties.version)

  def autoDependencyMap(): Map[Module, Seq[Dependency]] = {

    val default =
      if (defaultAutoDependencies)
        Map(
          Module.of("org.apache.spark", "*") -> Seq(Dependency.of(
            Module.of("sh.almond", s"almond-spark_$sbv"),
            ammSparkVersion
          )),
          Module.of("me.shadaj", "scalapy*") -> Seq(Dependency.of(
            Module.of("sh.almond", s"almond-scalapy_$sbv"),
            almondScalapyVersion
          )),
          Module.of("dev.scalapy", "scalapy*") -> Seq(Dependency.of(
            Module.of("sh.almond", s"almond-scalapy_$sbv"),
            almondScalapyVersion
          ))
        )
      else
        Map.empty[Module, Seq[Dependency]]

    val fromArgs = autoDependency
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { s =>
        s.split("=>") match {
          case Array(trigger, auto) =>
            val trigger0 = ModuleParser.javaOrScalaModule(trigger) match {
              case Left(err) =>
                sys.error(s"Malformed module '$trigger' in --auto-dependency argument '$s': $err")
              case Right(m) =>
                val mod0 = m.module(scala.util.Properties.versionNumberString)
                Module.of(mod0.organization.value, mod0.name.value, mod0.attributes.asJava)
            }
            val auto0 = DependencyParser.javaOrScalaDependencyParams(auto) match {
              case Left(err) =>
                sys.error(s"Malformed dependency '$auto' in --auto-dependency argument '$s': $err")
              case Right((d, _)) =>
                val dep = d.dependency(scala.util.Properties.versionNumberString)
                Dependency.of(dep.module.organization.value, dep.module.name.value, dep.version)
                  .withConfiguration(dep.configuration.value)
                  .withClassifier(dep.attributes.classifier.value)
                  .withType(dep.attributes.`type`.value)
            }
            trigger0 -> auto0
          case _ =>
            sys.error(s"Unrecognized --auto-dependency argument: $s")
        }
      }
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap

    default ++ fromArgs
  }

  def autoVersionsMap(): Map[Module, String] = {

    val default =
      if (defaultAutoVersions)
        Map(
          Module.of("sh.almond", s"almond-spark_$sbv")   -> Properties.ammoniteSparkVersion,
          Module.of("sh.almond", s"ammonite-spark_$sbv") -> Properties.ammoniteSparkVersion
        )
      else
        Map.empty[Module, String]

    val fromArgs = autoVersion
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { s =>
        val idx = s.lastIndexOf(':')
        if (idx < 0)
          sys.error(s"Malformed --auto-version argument '$s'")
        else {
          val before = s.substring(0, idx)
          val ver    = s.substring(idx + 1)
          val mod = ModuleParser.javaOrScalaModule(before) match {
            case Left(err) =>
              sys.error(s"Malformed module '$before' in --auto-version argument '$s': $err")
            case Right(m) =>
              val mod0 = m.module(scala.util.Properties.versionNumberString)
              Module.of(mod0.organization.value, mod0.name.value, mod0.attributes.asJava)
          }

          mod -> ver
        }
      }
      .toMap

    default ++ fromArgs
  }

  def forceProperties(): Map[String, String] =
    forceProperty
      .filter(_.nonEmpty)
      .map(_.split("=", 2))
      .map {
        case Array(k, v) =>
          k -> v
        case other =>
          sys.error(s"Malformed link: $other")
      }
      .toMap

  def mavenProfiles(): Map[String, Boolean] =
    profile
      .filter(_.nonEmpty)
      .map { p =>
        if (p.startsWith("!"))
          p.stripPrefix("!") -> false
        else
          p -> true
      }
      .toMap

  def extraLinks(): Seq[KernelInfo.Link] =
    link
      .map(_.split(Pattern.quote("|"), 2))
      .map {
        case Array(url, desc) =>
          KernelInfo.Link(desc, url)
        case other =>
          sys.error(s"Malformed link: $other")
      }

  def predefFiles(): Seq[Path] =
    predef.map { p =>
      val path = Paths.get(p)
      if (!Files.exists(path)) {
        System.err.println(s"Error: predef $p: not found")
        sys.exit(1)
      }
      if (!Files.isRegularFile(path)) {
        System.err.println(s"Error: predef $p: not a file")
        sys.exit(1)
      }
      path
    }

  def leftoverMessages0(): Seq[(Channel, RawMessage)] =
    leftoverMessages.toSeq.flatMap { strPath =>
      val path    = os.Path(strPath, os.pwd)
      val bytes   = os.read.bytes(path)
      val msgFile = readFromArray(bytes)(MessageFile.codec)
      msgFile.parsedMessages
    }

  def readKernelOptions(): Option[KernelOptions.AsJson] =
    kernelOptions.map { strPath =>
      val path  = os.Path(strPath, os.pwd)
      val bytes = os.read.bytes(path)
      readFromArray(bytes)(KernelOptions.AsJson.codec)
    }

  lazy val lingerDuration = linger
    .map(_.trim)
    .filter(_.nonEmpty)
    .map(Duration(_))
    .getOrElse(5.seconds)
}

object Options {

  implicit lazy val parser: Parser[Options] = Parser.derive
  implicit lazy val help: Help[Options] = Help.derive
    // not sure why the @ProgName annotation above isn't picked here
    .withProgName("almond")

}
