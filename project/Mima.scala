
import sys.process._

object Mima {

  private def stable(ver: String): Boolean =
    ver.exists(c => c != '0' && c != '.') &&
    ver
      .replace("-RC", "-")
      .forall(c => c == '.' || c == '-' || c.isDigit)

  def binaryCompatibilityVersions(contains: String): Set[String] =
    Seq("git", "tag", "--merged", "HEAD^", "--contains", contains)
      .!!
      .linesIterator
      .map(_.trim)
      .filter(_.startsWith("v"))
      .map(_.stripPrefix("v"))
      .filter(_ != "0.3.1") // Mima enabled right after it
      .filter(stable)
      .toSet

}
