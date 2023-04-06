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

  final class CannotGetKernelCommand extends InstallException(
        "Could not determine the command that launches the kernel. Run the kernel with coursier, or " +
          "pass the kernel command via --command first-arg --command second-arg …"
      )

}
