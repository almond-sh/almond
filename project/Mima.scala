
import sys.process._

object Mima {

  private def stable(ver: String): Boolean =
    ver.exists(c => c != '0' && c != '.') &&
    ver
      .replace("-RC", "-")
      .forall(c => c == '.' || c == '-' || c.isDigit)

  def binaryCompatibilityVersions(): Set[String] =
    Seq("git", "tag", "--merged", "HEAD^", "--contains", "v0.6.0")
      .!!
      .linesIterator
      .map(_.trim)
      .filter(_.startsWith("v"))
      .map(_.stripPrefix("v"))
      .filter(_ != "0.6.0") // Preserving compatibility right after it
      .filter(stable)
      .toSet

}
