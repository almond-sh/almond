package almond.kernel.util

import java.io.File
import java.nio.file.{Path, Paths}
import java.util.Locale

object JupyterPaths {

  // See https://docs.jupyter.org/en/latest/use/jupyter-directories.html#data-files and
  // https://github.com/jupyter/jupyter_core/blob/v5.9.1/jupyter_core/paths.py, which
  // we try to follow here.

  // Same as envset in jupyter_core
  private def envSet(name: String): Option[Boolean] =
    sys.env.get(name).map { value =>
      !Set("no", "n", "false", "off", "0", "0.0").contains(value.toLowerCase(Locale.ROOT))
    }

  private def nonEmptyEnv(name: String): Option[String] =
    sys.env.get(name).filter(_.nonEmpty)

  private def usePlatformDirs: Boolean =
    envSet("JUPYTER_PLATFORM_DIRS").getOrElse(false)

  private def home: Path =
    Paths.get(sys.props("user.home"))

  private def splitPathList(value: String): Seq[String] =
    value
      .split(java.util.regex.Pattern.quote(File.pathSeparator))
      .toSeq
      .map(_.stripSuffix(File.separator))
      .filter(_.nonEmpty)

  /** Python prefix of the environment Jupyter runs from, if known */
  private def sysPrefix: Option[Path] =
    sys.props.get("jupyter.sys.prefix")
      .orElse(nonEmptyEnv("CONDA_PREFIX"))
      .map(Paths.get(_))

  /** Jupyter data directory of the current user (`jupyter --data-dir`) */
  def userDataDir: Path =
    nonEmptyEnv("JUPYTER_DATA_DIR").map(Paths.get(_)).getOrElse {
      if (usePlatformDirs)
        OS.current match {
          case OS.Mac =>
            home.resolve("Library/Application Support/jupyter")
          case _: OS.Unix =>
            nonEmptyEnv("XDG_DATA_HOME")
              .map(Paths.get(_))
              .getOrElse(home.resolve(".local/share"))
              .resolve("jupyter")
          case OS.Windows =>
            nonEmptyEnv("LOCALAPPDATA")
              .map(Paths.get(_))
              .getOrElse(home.resolve("AppData").resolve("Local"))
              .resolve("jupyter")
        }
      else
        OS.current match {
          case OS.Mac =>
            home.resolve("Library/Jupyter")
          case _: OS.Unix =>
            nonEmptyEnv("XDG_DATA_HOME")
              .map(Paths.get(_))
              .getOrElse(home.resolve(".local/share"))
              .resolve("jupyter")
          case OS.Windows =>
            nonEmptyEnv("APPDATA") match {
              case Some(appData) =>
                Paths.get(appData, "jupyter")
              case None =>
                nonEmptyEnv("JUPYTER_CONFIG_DIR")
                  .map(Paths.get(_))
                  .getOrElse(home.resolve(".jupyter"))
                  .resolve("data")
            }
        }
    }

  def systemPaths: Seq[Path] =
    OS.current match {
      case OS.Mac if usePlatformDirs =>
        Seq(Paths.get("/Library/Application Support/jupyter/kernels"))
      case _: OS.Unix if usePlatformDirs =>
        nonEmptyEnv("XDG_DATA_DIRS")
          .map(splitPathList)
          .getOrElse(Seq("/usr/local/share", "/usr/share"))
          .map(Paths.get(_, "jupyter", "kernels"))
      case _: OS.Unix =>
        Seq(
          Paths.get("/usr/local/share/jupyter/kernels"),
          Paths.get("/usr/share/jupyter/kernels")
        )
      case OS.Windows =>
        // Since jupyter_core 5.8, %PROGRAMDATA% is only used if JUPYTER_USE_PROGRAMDATA is set,
        // and the Python prefix is used instead. Former versions always used %PROGRAMDATA%,
        // so we still use it if we don't know the Python prefix.
        val programData = nonEmptyEnv("PROGRAMDATA").map(Paths.get(_, "jupyter", "kernels"))
        val fromPrefix  = sysPrefix.map(_.resolve("share/jupyter/kernels"))
        if (envSet("JUPYTER_USE_PROGRAMDATA").getOrElse(false))
          programData.toSeq
        else
          fromPrefix.orElse(programData).toSeq
    }

  def userPath: Path =
    userDataDir.resolve("kernels")

  def envPaths: Seq[Path] = {

    val sysPrefixPath = sysPrefix.toSeq.map { prefix =>
      prefix.resolve("share/jupyter/kernels")
    }

    val jupyterPathEnv = nonEmptyEnv("JUPYTER_PATH").toSeq.flatMap(splitPathList).map {
      prefix =>
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
