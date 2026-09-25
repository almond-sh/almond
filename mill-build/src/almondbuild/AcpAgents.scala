package almondbuild

import mill.api.PathRef

import java.io.File

import scala.util.Properties

/** The ACP (Agent Client Protocol) agents Jupyter AI talks to: its Claude and Codex personas are
  * only enabled if the `claude-agent-acp` and `codex-acp` commands are on the `PATH` of the
  * JupyterLab server.
  *
  * They're npm packages, described by `examples/acp-agents/package.json`, with exact versions
  * pinned in `examples/acp-agents/package-lock.json`. They're installed with `npm ci` in a
  * directory of the build, rather than globally, and that directory's `node_modules/.bin` is added
  * to the `PATH` of JupyterLab.
  */
object AcpAgents {

  /** The `npm` command on the `PATH`, if any */
  def findNpm(): Option[os.Path] = {
    val names =
      if (Properties.isWin) Seq("npm.cmd", "npm.exe", "npm")
      else Seq("npm")
    val pathValue = sys.env
      .find(_._1.equalsIgnoreCase("PATH"))
      .map(_._2)
      .getOrElse("")
    pathValue
      .split(File.pathSeparator)
      .iterator
      .filter(_.nonEmpty)
      .flatMap(dir => names.iterator.map(name => java.nio.file.Paths.get(dir, name)))
      .find(p => java.nio.file.Files.isRegularFile(p) && java.nio.file.Files.isExecutable(p))
      .map(p => os.Path(p.toAbsolutePath))
  }

  /** Installs the agents described by the `package.json` and `package-lock.json` files in `dest`,
    * and returns the directory containing their commands.
    *
    * @return
    *   the `node_modules/.bin` directory, or `None` if `npm` isn't available, in which case the
    *   Claude and Codex personas of Jupyter AI are simply not enabled
    */
  def install(
    npm: Option[os.Path],
    packageJson: os.Path,
    packageLock: os.Path,
    dest: os.Path
  ): Option[os.Path] =
    npm match {
      case None =>
        System.err.println(
          "Warning: npm not found, not installing the ACP agents for the Claude and Codex " +
            "personas of Jupyter AI. Install Node.js 22 or later, and run this command again, to " +
            "get them."
        )
        None
      case Some(npm0) =>
        os.copy.over(packageJson, dest / "package.json", createFolders = true)
        os.copy.over(packageLock, dest / "package-lock.json", createFolders = true)
        System.err.println("Installing the ACP agents for Jupyter AI")
        os.proc(
          PathRef.toResolvedPathString(npm0),
          "ci",
          "--no-audit",
          "--no-fund",
          "--loglevel=error"
        ).call(
          cwd = dest,
          stdin = os.Inherit,
          stdout = os.Inherit,
          stderr = os.Inherit
        )
        Some(dest / "node_modules" / ".bin")
    }
}
