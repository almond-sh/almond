package almondbuild

import coursier.getcs.GetCs

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
    urlOpt.map(GetCs.download).getOrElse(GetCs.fromPath("cs"))
  }
}
