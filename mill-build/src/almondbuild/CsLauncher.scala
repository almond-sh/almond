package almondbuild

import coursier.cache.ArchiveCache
import coursier.getcs.GetCs
import coursier.util.Artifact

import java.util.Locale

import scala.util.Properties

object CsLauncher {

  /** Path to a `cs` launcher of the passed version, downloading it if needed. */
  def cs(version: String): String = {
    val arch = sys.props.getOrElse("os.arch", "").toLowerCase(Locale.ROOT)
    val urlOpt =
      if (arch == "aarch64" && Properties.isMac)
        // GetCs gets that one from VirtusLab/coursier-m1, which stopped publishing coursier
        // releases at 2.1.25-M1, while coursier publishes macOS / ARM launchers itself now
        Some(
          "https://github.com/coursier/coursier/releases/download/" +
            s"v$version/cs-aarch64-apple-darwin.gz"
        )
      else
        GetCs.url(arch, version, Properties.isWin, Properties.isMac, Properties.isLinux)
    urlOpt.map(download).getOrElse(GetCs.fromPath("cs"))
  }

  // Rather than GetCs.download, that was compiled against an older coursier, whose
  // ArchiveCache.apply isn't binary compatible with the one of the coursier Mill brings
  private def download(url: String): String = {
    val archiveCache = ArchiveCache()
    val f = archiveCache.get(Artifact(url)).unsafeRun()(using archiveCache.cache.ec) match {
      case Left(err) => throw new Exception(s"Error downloading $url", err)
      case Right(f)  => f
    }
    val exec =
      if (Properties.isWin && f.isDirectory && f.getName.endsWith(".zip"))
        f.listFiles.find(_.getName.endsWith(".exe")).getOrElse {
          sys.error(s"No .exe found under $f")
        }
      else
        f
    if (!Properties.isWin)
      exec.setExecutable(true)
    exec.toString
  }
}
