package almond.kernel.util

import java.nio.file.{Path, Paths}

object JupyterPaths {

  // See http://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs

  // FIXME On Windows, rely on https://github.com/soc/directories-jvm/blob/d302b1e93963c81ed511e072a52e95251b5d078b/src/main/java/io/github/soc/directories/Util.java#L110 ?
  // Not sure about the way this gets PROGRAMDATA and APPDATA, on Windows.

  def systemPaths: Seq[Path] =
    OS.current match {
      case _: OS.Unix =>
        Seq(
          Paths.get("/usr/local/share/jupyter/kernels"),
          Paths.get("/usr/share/jupyter/kernels")
        )
      case OS.Windows =>
        Seq(
          Paths.get(sys.env("PROGRAMDATA"), "jupyter", "kernels")
        )
    }

  def userPath: Path =
    OS.current match {
      case OS.Mac =>
        Paths.get(sys.props("user.home"), "Library", "Jupyter", "kernels")
      case _: OS.Unix =>
        Paths.get(sys.props("user.home"), ".local", "share", "jupyter", "kernels")
      case OS.Windows =>
        Paths.get(sys.env("APPDATA"), "jupyter", "kernels")
    }

  def envPaths: Seq[Path] = {

    val sysPrefixPath = sys.props.get("jupyter.sys.prefix").toSeq.map { prefix =>
      Paths.get(prefix, "share", "jupyter", "kernels")
    }

    val jupyterPathEnv = sys.env.get("JUPYTER_PATH").toSeq.map { prefix =>
      Paths.get(prefix, "kernels")
    }

    val jupyterPathProp = sys.props.get("jupyter.path").toSeq.map { prefix =>
      Paths.get(prefix, "kernels")
    }

    (sysPrefixPath ++ jupyterPathEnv ++ jupyterPathProp).distinct
  }

  def paths: Seq[Path] =
    (Seq(userPath) ++ envPaths ++ systemPaths).distinct

}
