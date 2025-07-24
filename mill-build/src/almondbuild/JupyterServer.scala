package almondbuild

import java.nio.file.*

object JupyterServer {

  def kernelId        = "scala-debug"
  def specialKernelId = "scala-special-debug"

  def writeKernelJson(
    launcher: Path,
    jupyterDir: Path,
    workspace: os.Path,
    publishVersion: String,
    kernelId: String,
    name: String,
    extraArgs: String*
  ): Unit = {
    val launcherPath = launcher.toAbsolutePath.toString
    val dir          = jupyterDir.resolve(s"kernels/$kernelId")
    Files.createDirectories(dir)
    val baseArgs = Seq(
      launcherPath.toString,
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
      s"ivy:${(workspace / "out/repo" / publishVersion).toNIO.toUri.toASCIIString}/[defaultPattern]"
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
    publishVersion: String
  ): Unit = {

    writeKernelJson(launcher, jupyterDir, workspace, publishVersion, kernelId, "Scala (sources)")
    writeKernelJson(
      specialLauncher,
      jupyterDir,
      workspace,
      publishVersion,
      specialKernelId,
      "Scala (special, sources)",
      "--quiet=false"
    )

    os.makeDir.all(workspace / "notebooks")
    val jupyterCommand = Seq("jupyter", "lab", "--notebook-dir", "notebooks")
    val b              = new ProcessBuilder((jupyterCommand ++ args)*).inheritIO()
    val env            = b.environment()
    env.put("JUPYTER_PATH", jupyterDir.toAbsolutePath.toString)
    b.directory(workspace.toIO)
    val p = b.start()
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
    publishVersion: String
  ): Unit = {

    writeKernelJson(launcher, jupyterDir, workspace, publishVersion, kernelId, "Scala (sources)")
    writeKernelJson(
      specialLauncher,
      jupyterDir,
      workspace,
      publishVersion,
      specialKernelId,
      "Scala (special, sources)"
    )

    val jupyterCommand = Seq("jupyter", "console", s"--kernel=$kernelId")
    val b   = new ProcessBuilder((jupyterCommand ++ args)*).directory(workspace.toIO).inheritIO()
    val env = b.environment()
    env.put("JUPYTER_PATH", jupyterDir.toAbsolutePath.toString)
    val p = b.start()
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
