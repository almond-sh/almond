package almondbuild

import coursier.cache.ArchiveCache
import coursier.util.{Artifact, Task}

import java.util.Locale

import scala.util.Properties

object UvLauncher {

  /** Path to a `uv` launcher of the passed version, downloading it if needed.
    *
    * Set the `ALMOND_UV` environment variable to the path of a `uv` binary to use that one instead.
    */
  def uv(version: String): os.Path =
    sys.env.get("ALMOND_UV").filter(_.nonEmpty) match {
      case Some(path) => os.Path(path, os.pwd)
      case None       => download(version)
    }

  private def target: String = {
    val arch = sys.props.getOrElse("os.arch", "").toLowerCase(Locale.ROOT) match {
      case "amd64" | "x86_64"  => "x86_64"
      case "aarch64" | "arm64" => "aarch64"
      case other               => sys.error(s"Unsupported CPU architecture for uv: $other")
    }
    if (Properties.isWin) s"$arch-pc-windows-msvc"
    else if (Properties.isMac) s"$arch-apple-darwin"
    else if (Properties.isLinux) s"$arch-unknown-linux-gnu"
    else sys.error(s"Unsupported OS for uv: ${Properties.osName}")
  }

  private def download(version: String): os.Path = {
    val ext          = if (Properties.isWin) "zip" else "tar.gz"
    val url          = s"https://github.com/astral-sh/uv/releases/download/$version/uv-$target.$ext"
    val archiveCache = ArchiveCache[Task]()
    val dir = archiveCache.get(Artifact(url)).unsafeRun()(using archiveCache.cache.ec) match {
      case Left(err)  => throw new Exception(s"Error downloading $url", err)
      case Right(dir) => os.Path(dir.getAbsoluteFile)
    }
    val name = if (Properties.isWin) "uv.exe" else "uv"
    // The tar.gz archives have a top-level "uv-<target>" directory, the zip ones don't
    Seq(dir / name, dir / s"uv-$target" / name).find(os.exists(_)).getOrElse {
      sys.error(s"$name not found in $url (extracted under $dir)")
    }
  }
}
