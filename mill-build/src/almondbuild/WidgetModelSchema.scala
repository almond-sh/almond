package almondbuild

import coursier.cache.FileCache
import coursier.util.Artifact

import java.security.MessageDigest

object WidgetModelSchema {

  /** URL of the model schema of the passed version of ipywidgets */
  def url(ipywidgetsVersion: String): String =
    "https://raw.githubusercontent.com/jupyter-widgets/ipywidgets/" +
      s"$ipywidgetsVersion/packages/schema/jupyterwidgetmodels.latest.json"

  /** Path to the model schema of the passed version of ipywidgets, downloading it if needed
    *
    * The schema is downloaded to coursier's cache. Its SHA-256 checksum is checked against
    * `sha256`, as the content behind a GitHub tag isn't guaranteed not to change.
    */
  def fetch(ipywidgetsVersion: String, sha256: String): os.Path = {
    val url0  = url(ipywidgetsVersion)
    val cache = FileCache()
    val file = cache.file(Artifact(url0)).run.unsafeRun()(using cache.ec) match {
      case Left(err)   => throw new Exception(s"Error downloading $url0", err)
      case Right(file) => os.Path(file.getAbsoluteFile)
    }
    val actualSha256 = MessageDigest.getInstance("SHA-256")
      .digest(os.read.bytes(file))
      .map(b => f"$b%02x")
      .mkString
    if (actualSha256 != sha256)
      sys.error(
        s"Unexpected SHA-256 checksum for $url0 (at $file): expected $sha256, got $actualSha256"
      )
    file
  }
}
