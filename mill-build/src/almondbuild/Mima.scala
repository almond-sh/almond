package almondbuild

import com.github.lolgab.mill.mima._
import mill._
import mill.define.{Discover, ExternalModule}

object Mima extends ExternalModule {
  private def stable(ver: String): Boolean =
    ver.exists(c => c != '0' && c != '.') &&
    ver
      .replace("-RC", "-")
      .forall(c => c == '.' || c == '-' || c.isDigit)

  def binaryCompatibilityVersions: T[Seq[String]] = Task(Seq.empty[String])
  // TODO Re-enable that
  // os.proc("git", "tag", "--merged", "HEAD^", "--contains", "v0.8.3")
  //   .call()
  //   .lines()
  //   .iterator
  //   .map(_.trim)
  //   .filter(_.startsWith("v"))
  //   .map(_.stripPrefix("v"))
  //   .filter(_ != "0.8.3") // Preserving compatibility right after it
  //   .filter(stable)
  //   .toVector

  lazy val millDiscover: Discover = Discover[this.type]
}
