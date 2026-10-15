package almond.kernel.install

import java.nio.file.Path

sealed abstract class InstallException(message: String) extends Exception(message)

object InstallException {

  final class JupyterDirectoryTypeNotFound(dirType: String) extends InstallException(
        s"No Jupyter directory $dirType found"
      )

  final class InstallDirAlreadyExists(dir: Path) extends InstallException(
        // FIXME we're hardcoding the force option name here…
        s"$dir already exists, pass --force to force erasing it"
      )

  final class CannotGetJupyterDirectories(details: String) extends InstallException(
        // FIXME we're hardcoding the require-jupyter option name here…
        s"Could not get the Jupyter directories, required by --require-jupyter: $details"
      )

  final class CannotFindGlobalJupyterDirectory extends InstallException(
        // FIXME we're hardcoding option names here…
        "Could not find a system-wide Jupyter kernels directory. Pass --jupyter-command with the " +
          "path of the jupyter command of your Jupyter installation, or --jupyter-path with the " +
          "kernels directory to install the kernel in. Alternatively, set " +
          "JUPYTER_USE_PROGRAMDATA=1 to install it under %PROGRAMDATA%\\jupyter\\kernels."
      )

  final class CannotGetKernelCommand extends InstallException(
        "Could not determine the command that launches the kernel. Run the kernel with coursier, or " +
          "pass the kernel command via --command first-arg --command second-arg …"
      )

}
