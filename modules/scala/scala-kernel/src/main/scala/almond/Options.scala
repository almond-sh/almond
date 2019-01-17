package almond

import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import almond.protocol.KernelInfo
import almond.kernel.install.{Options => InstallOptions}
import caseapp._
import caseapp.core.help.Help

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
  @HelpMessage("Name of a class loader set up with the -i option of coursier bootstrap or coursier launch, to be used from the session")
    specialLoader: String = "user",
  @HelpMessage("Start a metabrowse server for go to source navigation (linked from Jupyter inspections)")
    metabrowse: Boolean = false,
  @HelpMessage("Trap what user code sends to stdout and stderr")
    trapOutput: Boolean = false,
  @HelpMessage("Disable ammonite compilation cache")
    disableCache: Boolean = false
) {

  def autoDependencyMap(): Map[String, Seq[String]] =
    autoDependency
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { s =>
        s.split("=>") match {
          case Array(trigger, auto) =>
            trigger -> auto
          case _ =>
            sys.error(s"Unrecognized --auto-dependency argument: $s")
        }
      }
      .groupBy(_._1)
      .mapValues(_.map(_._2))
      .iterator
      .toMap

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

  implicit val help = Help[Options].copy(
    // not sure why the @ProgName annotation above isn't picked here
    progName = "almond"
  )

}
