package almondbuild

import mill.api.PathRef

import java.nio.file.*

object JupyterServer {

  def kernelId        = "scala-debug"
  def specialKernelId = "scala-special-debug"

  /** Runs `jupyter` from the uv-managed environment described by `examples/pyproject.toml`, so that
    * users don't need Jupyter (or even Python) installed: uv creates the environment on the fly,
    * with the versions pinned in `examples/uv.lock`.
    */
  def jupyterCommand(workspace: os.Path, jupyterArgs: String*): Seq[String] =
    Seq(
      "uv",
      "run",
      "--project",
      (workspace / "examples").toString,
      "--frozen",
      "jupyter"
    ) ++ jupyterArgs

  private def startProcess(b: ProcessBuilder): Process =
    try b.start()
    catch {
      case e: java.io.IOException if e.getMessage != null && e.getMessage.contains("\"uv\"") =>
        throw new Exception(
          "Cannot run 'uv', which is used to set up Jupyter. " +
            "Install it from https://docs.astral.sh/uv/ and make sure it is in your PATH.",
          e
        )
    }

  def writeKernelJson(
    launcher: Path,
    jupyterDir: Path,
    workspace: os.Path,
    localRepoRoot: os.Path,
    publishVersion: String,
    kernelId: String,
    name: String,
    extraArgs: String*
  ): Unit = {
    val dir = jupyterDir.resolve(s"kernels/$kernelId")
    Files.createDirectories(dir)
    val baseArgs = Seq(
      PathRef.toAbsString(os.Path(launcher.toAbsolutePath)),
      "--log",
      "debug",
      "--connection-file",
      "{connection_file}",
      "--variable-inspector",
      "--toree-magics",
      "--use-notebook-coursier-logger",
      "--silent-imports",
      "--use-notebook-coursier-logger",
      "--extra-repository",
      PathRef.toAbsNioPath(localRepoRoot).toUri.toASCIIString
    )
    val kernelJson = ujson.Obj(
      "language"     -> ujson.Str("scala"),
      "display_name" -> ujson.Str(name),
      "argv" -> ujson.Arr(
        (baseArgs ++ extraArgs).map(ujson.Str(_))*
      )
    ).render()
    Files.write(dir.resolve("kernel.json"), kernelJson.getBytes("UTF-8"))
    System.err.println(s"JUPYTER_PATH=$jupyterDir")
  }

  def jupyterServer(
    launcher: Path,
    specialLauncher: Path,
    jupyterDir: Path,
    args: Seq[String],
    workspace: os.Path,
    publishVersion: String,
    localRepoRoot: os.Path
  ): Unit = {

    writeKernelJson(
      launcher,
      jupyterDir,
      workspace,
      localRepoRoot,
      publishVersion,
      kernelId,
      "Scala (sources)"
    )
    writeKernelJson(
      specialLauncher,
      jupyterDir,
      workspace,
      localRepoRoot,
      publishVersion,
      specialKernelId,
      "Scala (special, sources)",
      "--quiet=false"
    )

    os.makeDir.all(workspace / "notebooks")
    val command = jupyterCommand(workspace, "lab", "--notebook-dir", "notebooks")
    val b       = new ProcessBuilder((command ++ args)*).inheritIO()
    val env     = b.environment()
    env.put("JUPYTER_PATH", jupyterDir.toAbsolutePath.toString)
    b.directory(workspace.toIO)
    val p = startProcess(b)
    val hook: Thread = new Thread("jupyter-stop") {
      override def run() =
        if (p.isAlive)
          p.destroy()
    }
    Runtime.getRuntime.addShutdownHook(hook)
    val retCode = p.waitFor()
    Runtime.getRuntime.removeShutdownHook(hook)
    if (retCode != 0)
      System.err.println(s"Jupyter command exited with code $retCode")
  }

  def jupyterConsole(
    launcher: Path,
    specialLauncher: Path,
    jupyterDir: Path,
    args: Seq[String],
    workspace: os.Path,
    publishVersion: String,
    localRepoRoot: os.Path
  ): Unit = {

    writeKernelJson(
      launcher,
      jupyterDir,
      workspace,
      localRepoRoot,
      publishVersion,
      kernelId,
      "Scala (sources)"
    )
    writeKernelJson(
      specialLauncher,
      jupyterDir,
      workspace,
      localRepoRoot,
      publishVersion,
      specialKernelId,
      "Scala (special, sources)"
    )

    val command = jupyterCommand(workspace, "console", s"--kernel=$kernelId")
    val b       = new ProcessBuilder((command ++ args)*).directory(workspace.toIO).inheritIO()
    val env     = b.environment()
    env.put("JUPYTER_PATH", jupyterDir.toAbsolutePath.toString)
    val p = startProcess(b)
    val hook: Thread = new Thread("jupyter-stop") {
      override def run() =
        if (p.isAlive)
          p.destroy()
    }
    Runtime.getRuntime.addShutdownHook(hook)
    val retCode = p.waitFor()
    Runtime.getRuntime.removeShutdownHook(hook)
    if (retCode != 0)
      System.err.println(s"Jupyter command exited with code $retCode")
  }
}
