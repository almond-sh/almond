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
  def jupyterCommand(uv: os.Path, workspace: os.Path, jupyterArgs: String*): Seq[String] =
    Seq(
      uv.toString,
      "run",
      "--project",
      (workspace / "examples").toString,
      "--frozen",
      "jupyter"
    ) ++ jupyterArgs

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

  /** Extracts a `--base-address=…` (or `--base-address …`) option from the passed arguments, if
    * any, and returns it along with the remaining arguments.
    */
  private def extractBaseAddress(args: Seq[String]): (Option[String], Seq[String]) = {
    val opt = "--base-address"
    args.indexWhere(a => a == opt || a.startsWith(opt + "=")) match {
      case -1 => (None, args)
      case idx =>
        val arg = args(idx)
        if (arg == opt)
          if (idx + 1 < args.length) (Some(args(idx + 1)), args.patch(idx, Nil, 2))
          else sys.error(s"Missing value for $opt")
        else
          (Some(arg.stripPrefix(opt + "=")), args.patch(idx, Nil, 1))
    }
  }

  /** JupyterLab options making it assume it's reached at the passed address, typically via a
    * reverse proxy handling TLS (Tailscale serve, …): URLs are displayed with that address, its
    * origin is accepted for CORS / websocket connections, non-local `Host` headers are accepted,
    * and the `X-Forwarded-*` headers set by the proxy are trusted.
    */
  private def baseAddressOptions(baseAddress: String): Seq[String] = {
    val uri = new java.net.URI(baseAddress)
    if (uri.getScheme == null || uri.getHost == null)
      sys.error(s"Invalid base address '$baseAddress', expected something like https://host:port")
    val origin = {
      val port = if (uri.getPort == -1) "" else s":${uri.getPort}"
      s"${uri.getScheme}://${uri.getHost}$port"
    }
    val basePath   = Option(uri.getPath).map(_.stripSuffix("/")).filter(_.nonEmpty)
    val baseUrlOpt = basePath.map(path => s"--ServerApp.base_url=$path/").toSeq
    Seq(
      s"--ServerApp.custom_display_url=$baseAddress",
      s"--ServerApp.allow_origin=$origin",
      "--ServerApp.allow_remote_access=True",
      "--ServerApp.trust_xheaders=True"
    ) ++ baseUrlOpt
  }

  def jupyterServer(
    uv: os.Path,
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
    val (baseAddressOpt, args0) = extractBaseAddress(args)
    val command = jupyterCommand(uv, workspace, "lab", "--notebook-dir", "notebooks") ++
      baseAddressOpt.toSeq.flatMap(baseAddressOptions)
    val b = new ProcessBuilder((command ++ args0)*).inheritIO()
    val env     = b.environment()
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
    uv: os.Path,
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

    val command = jupyterCommand(uv, workspace, "console", s"--kernel=$kernelId")
    val b       = new ProcessBuilder((command ++ args)*).directory(workspace.toIO).inheritIO()
    val env     = b.environment()
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
