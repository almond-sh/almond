package almond.lsp

import almond.logger.LoggerContext

import java.io.File
import java.util.Locale

/** How the Plasmon language server is run */
sealed abstract class PlasmonLauncher extends Product with Serializable {

  /** The command running Plasmon
    *
    * Can take a while, and throw, as this can download Plasmon.
    */
  def command(logCtx: LoggerContext): Seq[String]
}

object PlasmonLauncher {

  /** Runs Plasmon with a launcher we're given (its JVM launcher, say) */
  final case class Command(launcher: String) extends PlasmonLauncher {
    def command(logCtx: LoggerContext): Seq[String] = Seq(launcher)
  }

  /** Runs the native launcher of Plasmon from one of its GitHub releases
    *
    * It's downloaded and uncompressed by the coursier archive cache.
    *
    * @param tag
    *   tag of the release, like "nightly"
    * @param changing
    *   whether the archives of the release get updated, like those of the nightly release do: these
    *   are then checked for updates once their cached version gets older than the coursier TTL (24
    *   hours by default)
    */
  final case class Tag(tag: String, changing: Boolean) extends PlasmonLauncher {
    def command(logCtx: LoggerContext): Seq[String] = {
      val log = logCtx(getClass)
      val url = launcherUrl(tag).getOrElse {
        throw new Exception(
          s"No native launcher of Plasmon for ${sys.props("os.name")} / ${sys.props("os.arch")}" +
            ", pass one with --plasmon"
        )
      }
      log.info(s"Getting Plasmon from $url")
      val extracted = coursierapi.ArchiveCache.create()
        .get(coursierapi.Artifact.of(url, changing, false))
      val launcher =
        if (extracted.isDirectory) {
          val candidates = Seq("plasmon.exe", "plasmon").map(new File(extracted, _))
          candidates.find(_.isFile).getOrElse {
            throw new Exception(s"No Plasmon launcher found in $extracted (from $url)")
          }
        }
        else
          extracted
      // The archive cache doesn't keep the executable bit (single gzipped files don't have any)
      if (!launcher.canExecute() && !launcher.setExecutable(true))
        log.warn(s"Could not make $launcher executable")
      log.info(s"Using Plasmon launcher $launcher")
      Seq(launcher.getAbsolutePath)
    }
  }

  def releasesUrl = "https://github.com/alexarchambault/plasmon/releases"

  /** The URL of the native launcher of Plasmon for the current OS and CPU in the release `tag`, if
    * there's one
    */
  def launcherUrl(tag: String): Option[String] = {
    val os = sys.props.getOrElse("os.name", "").toLowerCase(Locale.ROOT)
    val arch = sys.props.getOrElse("os.arch", "").toLowerCase(Locale.ROOT) match {
      case "amd64" | "x86_64"  => "x86_64"
      case "aarch64" | "arm64" => "aarch64"
      case other               => other
    }
    val nameOpt =
      if (os.contains("linux"))
        arch match {
          case "x86_64" | "aarch64" => Some(s"plasmon-$arch-pc-linux.gz")
          case _                    => None
        }
      else if (os.contains("mac"))
        arch match {
          // no ARM launcher for macOS for now, the x86_64 one runs via Rosetta there
          case "x86_64" | "aarch64" => Some("plasmon-x86_64-apple-darwin.gz")
          case _                    => None
        }
      else if (os.contains("windows"))
        arch match {
          case "x86_64" => Some("plasmon-x86_64-pc-win32.zip")
          case _        => None
        }
      else
        None
    nameOpt.map(name => s"$releasesUrl/download/$tag/$name")
  }
}
