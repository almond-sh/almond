package almondbuild

import mill.api.PathRef

import java.io.File

import scala.jdk.OptionConverters.*
import scala.util.Properties

object JupyterServer {

  def kernelId        = "scala-debug"
  def specialKernelId = "scala-special-debug"

  /** A command to run JupyterLab: `command` is to be run from the `cwd` directory, with the `env`
    * variables added to the environment.
    */
  final case class Command(
    cwd: os.Path,
    env: Map[String, String],
    command: Seq[String]
  ) {

    /** This command as shell words: a `cd` to `cwd`, `VAR=value` assignments for `env`, then the
      * command itself, each of them quoted as needed. Join them with spaces and pass the result to
      * `eval` in a POSIX shell (bash, zsh, …) to run the command.
      *
      * Paths are made absolute: from a Mill task, `os.Path#toString` gives paths under the
      * workspace relative to the task sandbox (`../mill-workspace/…`), which don't resolve from the
      * shell the command is run in.
      */
    def shellWords: Seq[String] =
      Seq(s"cd ${Command.shellQuote(PathRef.toResolvedPathString(cwd))} &&") ++
        env.toSeq.map { case (k, v) => s"$k=${Command.shellQuote(v)}" } ++
        command.map(Command.shellQuote)

    /** This command as a single shell command line, to pass to `eval` in a POSIX shell (bash, zsh,
      * …)
      */
    def shellCommand: String =
      shellWords.mkString(" ")
  }
  object Command {
    private def isSafeShellChar(c: Char): Boolean =
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      "_@%+=:,./-".contains(c)
    private def shellQuote(s: String): String =
      if (s.nonEmpty && s.forall(isSafeShellChar)) s
      else "'" + s.replace("'", "'\\''") + "'"
  }

  /** Runs `jupyter` from the uv-managed environment described by `examples/pyproject.toml`, so that
    * users don't need Jupyter (or even Python) installed: uv creates the environment on the fly,
    * with the versions pinned in `examples/uv.lock`. `groups` are dependency groups of
    * `examples/pyproject.toml` to install on top of the main dependencies.
    */
  def jupyterCommand(
    uv: os.Path,
    workspace: os.Path,
    groups: Seq[String],
    jupyterArgs: String*
  ): Seq[String] =
    uvRunCommand(uv, workspace, groups) ++ Seq("jupyter") ++ jupyterArgs

  private def uvRunCommand(uv: os.Path, workspace: os.Path, groups: Seq[String]): Seq[String] =
    Seq(
      PathRef.toResolvedPathString(uv),
      "run",
      "--project",
      PathRef.toResolvedPathString(workspace / "examples"),
      "--frozen"
    ) ++
      groups.flatMap(group => Seq("--group", group))

  /** Makes the JupyterLab settings in `examples/jupyterlab-overrides.json` (theme following the
    * system one, 2-space indentation, …) the defaults of the uv-managed environment, for both
    * JupyterLab and the Jupyter Notebook UI. Settings changed by users still take precedence.
    *
    * These are written to the `overrides.d` directory of the JupyterLab application settings, which
    * lives in the Python environment (`<sys.prefix>/share/jupyter/lab/settings` usually):
    * JupyterLab only reads default overrides from there.
    */
  private def writeSettingsOverrides(
    uv: os.Path,
    workspace: os.Path,
    groups: Seq[String]
  ): Unit = {
    val appDir = os.proc(
      uvRunCommand(uv, workspace, groups),
      "python",
      "-c",
      "from jupyterlab.commands import get_app_dir; print(get_app_dir())"
    ).call(cwd = workspace, stderr = os.Inherit).out.trim()
    os.copy.over(
      workspace / "examples" / "jupyterlab-overrides.json",
      os.Path(appDir) / "settings" / "overrides.d" / "almond.json",
      createFolders = true
    )
  }

  /** Dependency group of `examples/pyproject.toml` with Jupyter AI, installed for JupyterLab */
  private def aiGroup = "ai"

  def writeKernelJson(
    launcher: os.Path,
    jupyterDir: os.Path,
    workspace: os.Path,
    localRepoRoot: os.Path,
    publishVersion: String,
    kernelId: String,
    name: String,
    extraArgs: String*
  ): Unit = {
    val dir = jupyterDir / "kernels" / kernelId
    val baseArgs = Seq(
      PathRef.toResolvedPathString(launcher),
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
      java.nio.file.Paths.get(PathRef.toResolvedPathString(localRepoRoot)).toUri.toASCIIString
    )
    val kernelJson = ujson.Obj(
      "language"     -> ujson.Str("scala"),
      "display_name" -> ujson.Str(name),
      "argv" -> ujson.Arr(
        (baseArgs ++ extraArgs).map(ujson.Str(_))*
      )
    ).render()
    os.write.over(dir / "kernel.json", kernelJson, createFolders = true)
    System.err.println(s"JUPYTER_PATH=${PathRef.toResolvedPathString(jupyterDir)}")
  }

  /** Environment for Jupyter: the JVM at `javaHome` (for the kernels it starts), the kernel specs
    * under `jupyterDir`, and the commands in `extraPath` (the ACP agents Jupyter AI talks to, …)
    */
  private def jupyterEnvironment(
    javaHome: os.Path,
    jupyterDir: os.Path,
    extraPath: Seq[os.Path] = Nil
  ): Map[String, String] = {
    val javaEnv = JavaHomes.environment(javaHome)
    val javaEnv0 =
      if (extraPath.isEmpty) javaEnv
      else {
        // JavaHomes.environment always sets PATH, possibly spelled differently on Windows
        val (pathKey, pathValue) = javaEnv.find(_._1.equalsIgnoreCase("PATH")).get
        val newPathValue =
          (extraPath.map(PathRef.toResolvedPathString(_)) :+ pathValue).mkString(File.pathSeparator)
        javaEnv + (pathKey -> newPathValue)
      }
    javaEnv0 + ("JUPYTER_PATH" -> PathRef.toResolvedPathString(jupyterDir))
  }

  /** Extracts a `--classic` flag from the passed arguments, if any: whether the Jupyter Notebook UI
    * (the classic one, at `/tree`) should be the default UI rather than JupyterLab (at `/lab`).
    * Both are served by the same server either way. Returns it along with the remaining arguments.
    */
  private def extractClassic(args: Seq[String]): (Boolean, Seq[String]) = {
    val flag = "--classic"
    (args.contains(flag), args.filterNot(_ == flag))
  }

  /** JupyterLab option making the Jupyter Notebook UI the one the server root redirects to, and the
    * one the URLs the server prints point at. This needs to be set on `LabApp` rather than
    * `ServerApp`: the app the server is started with (`jupyter lab` here) pushes its own
    * `default_url` in the server config, overriding a `--ServerApp.default_url=…` on the command
    * line.
    */
  private def classicOptions: Seq[String] =
    Seq("--LabApp.default_url=/tree")

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

  /** Runs the passed interactive Jupyter command from `workspace`, with the raw terminal I/O
    * inherited (rather than mill's redirected streams, which `os.Inherit` would use), killing it if
    * the JVM exits first (upon Ctrl-C for example). The kernels Jupyter starts pick up the JVM at
    * `javaHome`, via `JAVA_HOME` and `PATH`.
    */
  private def runJupyter(
    command: Seq[String],
    workspace: os.Path,
    jupyterDir: os.Path,
    javaHome: os.Path
  ): Unit = {
    System.err.println(s"JAVA_HOME=${PathRef.toResolvedPathString(javaHome)}")
    val proc = os.proc(command).spawn(
      cwd = workspace,
      env = jupyterEnvironment(javaHome, jupyterDir),
      stdin = os.InheritRaw,
      stdout = os.InheritRaw,
      stderr = os.InheritRaw
    )
    val hook: Thread = new Thread("jupyter-stop") {
      override def run() =
        if (proc.isAlive())
          proc.destroy()
    }
    Runtime.getRuntime.addShutdownHook(hook)
    proc.waitFor()
    Runtime.getRuntime.removeShutdownHook(hook)
    val retCode = proc.exitCode()
    if (retCode != 0)
      System.err.println(s"Jupyter command exited with code $retCode")
  }

  // JupyterLab runs in the background, the same way `runBackground` works in Mill: it is
  // started via Mill's `MillBackgroundWrapper` (in "subprocess" mode), which keeps track of
  // the running process in the files below. The wrapper kills JupyterLab and exits as soon as
  // the "newest PID" file doesn't contain its own PID anymore, which happens when a new server
  // takes over, when `jupyterStop` is called, or when the directory gets cleaned.
  private def newestPidFile(backgroundDir: os.Path)  = backgroundDir / "newest-pid"
  private def currentPidFile(backgroundDir: os.Path) = backgroundDir / "currently-running-pid"
  def stderrLog(backgroundDir: os.Path): os.Path     = backgroundDir / "stderr.log"

  private def runningProcess(backgroundDir: os.Path): Option[ProcessHandle] =
    Option(currentPidFile(backgroundDir))
      .filter(os.exists(_))
      .flatMap(f => os.read(f).trim.toLongOption)
      .flatMap(pid => ProcessHandle.of(pid).toScala)
      .filter(_.isAlive)

  /** Stops the JupyterLab server started in the background from `backgroundDir`, if any.
    *
    * @return
    *   whether a server was running
    */
  def stopBackground(backgroundDir: os.Path): Boolean =
    runningProcess(backgroundDir) match {
      case None => false
      case Some(wrapper) =>
        os.write.over(newestPidFile(backgroundDir), "stopped", createFolders = true)
        val deadline = System.currentTimeMillis() + 10000L
        while (wrapper.isAlive && System.currentTimeMillis() < deadline)
          Thread.sleep(100L)
        if (wrapper.isAlive) {
          wrapper.descendants().forEach(_.destroyForcibly())
          wrapper.destroyForcibly()
        }
        true
    }

  private def startBackground(
    javaHome: os.Path,
    wrapperClassPath: Seq[os.Path],
    backgroundDir: os.Path,
    command: Seq[String],
    cwd: os.Path,
    env: Map[String, String]
  ): Seq[os.Path] = {
    // Stop the current server first, so that the logs below only contain the new server's output
    if (stopBackground(backgroundDir))
      System.err.println("Stopped the previous JupyterLab server")
    os.makeDir.all(backgroundDir)
    val stdoutLog  = backgroundDir / "stdout.log"
    val stderrLog0 = stderrLog(backgroundDir)
    val javaExe    = javaHome / "bin" / (if (Properties.isWin) "java.exe" else "java")
    val wrapperArgs = Seq(
      newestPidFile(backgroundDir),
      currentPidFile(backgroundDir),
      backgroundDir / "lock",
      backgroundDir / "log"
    ).map(PathRef.toResolvedPathString(_))
    val proc = os.proc(
      PathRef.toResolvedPathString(javaExe),
      "-cp",
      wrapperClassPath.map(PathRef.toResolvedPathString(_)).mkString(File.pathSeparator),
      "mill.javalib.backgroundwrapper.MillBackgroundWrapper",
      wrapperArgs,
      "<subprocess>",
      command
    ).spawn(
      cwd = cwd,
      env = env,
      stdin = "",
      stdout = stdoutLog,
      stderr = stderrLog0,
      destroyOnExit = false
    )

    // Wait for JupyterLab to print the URLs it can be reached at, and print them
    val deadline = System.currentTimeMillis() + 120000L
    def urlLines(): Seq[String] = {
      val lines = if (os.exists(stderrLog0)) os.read.lines(stderrLog0) else Nil
      lines.dropWhile(!_.contains("is running at")).drop(1).takeWhile(_.contains("://"))
    }
    var urls = urlLines()
    while (urls.isEmpty && proc.isAlive() && System.currentTimeMillis() < deadline) {
      Thread.sleep(500L)
      urls = urlLines()
    }
    if (!proc.isAlive()) {
      val log = if (os.exists(stderrLog0)) os.read.lines(stderrLog0).takeRight(30) else Nil
      System.err.println(log.mkString(System.lineSeparator()))
      sys.error(s"JupyterLab exited early, see $stderrLog0")
    }
    val logFiles = Seq(stdoutLog, stderrLog0)
    val suffix   = if (urls.isEmpty) "" else " at:"
    System.err.println(s"JupyterLab is running in the background$suffix")
    for (line <- urls)
      System.err.println("  " + line.trim)
    System.err.println(
      "JupyterLab is under /lab, and the Jupyter Notebook (classic) UI under /tree: " +
        "switch between them from the View menu (pass --classic to land on /tree)"
    )
    val followCommand =
      if (Properties.isWin) s"Get-Content -Wait $stderrLog0" // PowerShell
      else s"tail -f ${logFiles.mkString(" ")}"
    System.err.println(s"Its output goes to ${logFiles.mkString(" and ")}, follow it with")
    System.err.println(s"  $followCommand")
    System.err.println(
      "Stop it with './mill dev.jupyterStop', or run this command again to restart it"
    )
    logFiles
  }

  private def writeKernelJsons(
    launcher: os.Path,
    specialLauncher: os.Path,
    jupyterDir: os.Path,
    workspace: os.Path,
    publishVersion: String,
    localRepoRoot: os.Path,
    specialExtraArgs: String*
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
      specialExtraArgs*
    )
  }

  /** Writes the kernel specs, and returns the command to run JupyterLab with them.
    *
    * The server also serves the Jupyter Notebook UI (the classic one), under `/tree`. `args` may
    * contain `--base-address=…` and `--classic`, handled here, the rest is passed to JupyterLab.
    *
    * JupyterLab comes with Jupyter AI. Its Claude and Codex personas are enabled if `acpAgentsBin`,
    * added to the `PATH` of JupyterLab, contains the `claude-agent-acp` and `codex-acp` commands
    * (see [[AcpAgents]]).
    */
  def jupyterLabCommand(
    uv: os.Path,
    javaHome: os.Path,
    launcher: os.Path,
    specialLauncher: os.Path,
    jupyterDir: os.Path,
    args: Seq[String],
    workspace: os.Path,
    publishVersion: String,
    localRepoRoot: os.Path,
    acpAgentsBin: Option[os.Path]
  ): Command = {

    writeKernelJsons(
      launcher,
      specialLauncher,
      jupyterDir,
      workspace,
      publishVersion,
      localRepoRoot,
      "--quiet=false"
    )

    writeSettingsOverrides(uv, workspace, Seq(aiGroup))

    os.makeDir.all(workspace / "notebooks")
    val (baseAddressOpt, args0) = extractBaseAddress(args)
    val (classic, args1)        = extractClassic(args0)
    val command =
      jupyterCommand(uv, workspace, Seq(aiGroup), "lab", "--notebook-dir", "notebooks") ++
        baseAddressOpt.toSeq.flatMap(baseAddressOptions) ++
        (if (classic) classicOptions else Nil) ++
        args1
    Command(
      workspace,
      jupyterEnvironment(javaHome, jupyterDir, acpAgentsBin.toSeq),
      command
    )
  }

  /** Starts a JupyterLab server in the background, and returns the files its output goes to */
  def jupyterServer(
    uv: os.Path,
    javaHome: os.Path,
    wrapperClassPath: Seq[os.Path],
    backgroundDir: os.Path,
    launcher: os.Path,
    specialLauncher: os.Path,
    jupyterDir: os.Path,
    args: Seq[String],
    workspace: os.Path,
    publishVersion: String,
    localRepoRoot: os.Path,
    acpAgentsBin: Option[os.Path]
  ): Seq[os.Path] = {
    val cmd = jupyterLabCommand(
      uv,
      javaHome,
      launcher,
      specialLauncher,
      jupyterDir,
      args,
      workspace,
      publishVersion,
      localRepoRoot,
      acpAgentsBin
    )
    System.err.println(s"JAVA_HOME=${PathRef.toResolvedPathString(javaHome)}")
    startBackground(
      javaHome,
      wrapperClassPath,
      backgroundDir,
      cmd.command,
      cmd.cwd,
      cmd.env
    )
  }

  def jupyterConsole(
    uv: os.Path,
    javaHome: os.Path,
    launcher: os.Path,
    specialLauncher: os.Path,
    jupyterDir: os.Path,
    args: Seq[String],
    workspace: os.Path,
    publishVersion: String,
    localRepoRoot: os.Path
  ): Unit = {

    writeKernelJsons(
      launcher,
      specialLauncher,
      jupyterDir,
      workspace,
      publishVersion,
      localRepoRoot
    )

    val command = jupyterCommand(uv, workspace, Nil, "console", s"--kernel=$kernelId") ++ args
    runJupyter(command, workspace, jupyterDir, javaHome)
  }
}
