package almondbuild

import com.github.lolgab.mill.mima.*
import mill.*
import mill.api.*

object Mima extends ExternalModule {
  private def stable(ver: String): Boolean =
    ver.exists(c => c != '0' && c != '.') &&
    ver
      .replace("-RC", "-")
      .forall(c => c == '.' || c == '-' || c.isDigit)

  def binaryCompatibilityVersions: T[Seq[String]] = Task.Input {
    os.proc("git", "tag", "--merged", "HEAD^", "--contains", "v0.14.0")
      .call()
      .out
      .lines()
      .iterator
      .map(_.trim)
      .filter(_.startsWith("v"))
      .map(_.stripPrefix("v"))
      .filter(_ != "0.14.0") // borked?
      .filter(stable)
      .toVector
  }

  lazy val millDiscover: Discover = Discover[this.type]
}
