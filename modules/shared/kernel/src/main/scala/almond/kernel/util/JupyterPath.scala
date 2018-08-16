package almond.kernel.util

import java.nio.file.Path

sealed abstract class JupyterPath(val name: String) extends Product with Serializable {
  def paths: Seq[Path]
}

object JupyterPath {
  case object System extends JupyterPath("system") {
    def paths: Seq[Path] =
      JupyterPaths.systemPaths
  }
  case object Env extends JupyterPath("environment") {
    def paths: Seq[Path] =
      JupyterPaths.envPaths
  }
  case object User extends JupyterPath("user") {
    def paths: Seq[Path] =
      Seq(JupyterPaths.userPath)
  }
}
