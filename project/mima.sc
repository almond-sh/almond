import $ivy.`com.github.lolgab::mill-mima::0.1.0`

import com.github.lolgab.mill.mima._
import sys.process._

private def stable(ver: String): Boolean =
  ver.exists(c => c != '0' && c != '.') &&
  ver
    .replace("-RC", "-")
    .forall(c => c == '.' || c == '-' || c.isDigit)

lazy val binaryCompatibilityVersions: Seq[String] = Nil
Seq("git", "tag", "--merged", "HEAD^", "--contains", "v0.8.3")
  .!!
  .linesIterator
  .map(_.trim)
  .filter(_.startsWith("v"))
  .map(_.stripPrefix("v"))
  .filter(_ != "0.8.3") // Preserving compatibility right after it
  .filter(stable)
  .toVector
