package almond

import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import almond.api.Properties
import almond.protocol.KernelInfo
import almond.kernel.install.{Options => InstallOptions}
import caseapp._
import caseapp.core.help.Help
import coursierapi.{Dependency, Module}
import coursier.parse.{DependencyParser, ModuleParser}

import scala.collection.compat._
import scala.jdk.CollectionConverters._

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
  @HelpMessage("Force Maven properties during dependency resolution")
    forceProperty: List[String] = Nil,
  @HelpMessage("Enable Maven profile (start with ! to disable)")
    profile: List[String] = Nil,
  @HelpMessage("Log level (one of none, error, warn, info, debug)")
    log: String = "warn",
  @HelpMessage("Send log to a file rather than stderr")
  @ValueDescription("/path/to/log-file")
    logTo: Option[String],
  connectionFile: Option[String] = None,
  // For class loader isolation, the user code is loaded from the classloader of the api module.
  // If the right -i / -I options are passed to coursier bootstrap when generating a launcher, that loader
  // only sees the api module and its dependencies, rather than the full classpath of almond.
  @HelpMessage("Use class loader that loaded the api module rather than the context class loader")
    specificLoader: Boolean = true,
  @HelpMessage("Start a metabrowse server for go to source navigation (linked from Jupyter inspections, server is started upon first inspection)")
    metabrowse: Boolean = true,
  @HelpMessage("Trap what user code sends to stdout and stderr")
    trapOutput: Boolean = false,
  @HelpMessage("Disable ammonite compilation cache")
    disableCache: Boolean = false,
  @HelpMessage("Whether to automatically update lazy val-s upon computation")
    autoUpdateLazyVals: Boolean = true,
  @HelpMessage("Whether to automatically update var-s upon change")
    autoUpdateVars: Boolean = true,
  @HelpMessage("Whether to process format requests with scalafmt")
    scalafmt: Boolean = true
) {

  private lazy val sbv = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  def autoDependencyMap(): Map[Module, Seq[Dependency]] = {

    val default =
      if (defaultAutoDependencies)
        Map(
          Module.of("org.apache.spark", "*") -> Seq(Dependency.of(Module.of("sh.almond", s"almond-spark_$sbv"), Properties.version))
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
          Module.of("sh.almond", s"almond-spark_$sbv") -> Properties.version,
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
          val ver = s.substring(idx + 1)
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

}

object Options {

  implicit val help = Help[Options]
    // not sure why the @ProgName annotation above isn't picked here
    .withProgName("almond")

}
