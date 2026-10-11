package almondbuild

import java.io.File

import scala.jdk.OptionConverters.*
import scala.util.Properties

object JupyterServer {

  def kernelId        = "scala-debug"
  def specialKernelId = "scala-special-debug"

  /** The kernel id of the kernel running a given full Scala version, when several kernels are
    * registered at once.
    */
  def kernelId(scalaVersion: String): String =
    s"$kernelId-${ScalaVersions.binarySuffix(scalaVersion)}"

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
      Seq(s"cd ${Command.shellQuote(cwd.toString)} &&") ++
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

  def uvRunCommand(uv: os.Path, workspace: os.Path, groups: Seq[String]): Seq[String] =
    Seq(
      uv.toString,
      "run",
      "--project",
      (workspace / "examples").toString,
      "--frozen"
    ) ++
      groups.flatMap(group => Seq("--group", group))

  /** The directory with the commands (`jupyter`, …) of the uv-managed environment of
    * [[jupyterCommand]] (with the dependency groups `groups`)
    */
  private def jupyterScriptsDir(uv: os.Path, workspace: os.Path, groups: Seq[String]): os.Path = {
    val scriptsDir = os.proc(
      uvRunCommand(uv, workspace, groups),
      "python",
      "-c",
      "import sysconfig; print(sysconfig.get_path('scripts'))"
    ).call(cwd = workspace, stderr = os.Inherit).out.trim()
    os.Path(scriptsDir)
  }

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

  /** Makes the example notebooks of `examples` available from JupyterLab, under
    * `notebooks/examples`: a symbolic link to `examples`, so that changes made from JupyterLab end
    * up in the example notebooks themselves. Where symbolic links can't be created (Windows without
    * developer mode, …), the notebooks get copied there instead.
    *
    * Nothing is done if `notebooks/examples` already exists, so that a copy edited from JupyterLab
    * doesn't get overwritten.
    */
  private def linkExamples(workspace: os.Path): Unit = {
    val examplesDir = workspace / "examples"
    val dest        = workspace / "notebooks" / "examples"
    if (!os.exists(dest, followLinks = false))
      try os.symlink(dest, os.up / "examples")
      catch {
        case e @ (_: UnsupportedOperationException | _: java.io.IOException) =>
          System.err.println(
            s"Could not create a symbolic link to $examplesDir at $dest ($e), " +
              "copying the example notebooks there instead"
          )
          os.makeDir.all(dest)
          for (notebook <- os.list(examplesDir) if notebook.last.endsWith(".ipynb"))
            os.copy(notebook, dest / notebook.last)
      }
  }

  /** Dependency groups of `examples/pyproject.toml` installed for JupyterLab: JupyterLab extensions
    * (the variable inspector, …), and Jupyter AI
    */
  def labGroups = Seq("lab", "ai")

  /** Where the kernels are installed from: the almond modules built from sources, published to a
    * local Maven repository
    *
    * @param cs
    *   the `cs` launcher installing the kernels
    * @param appChannel
    *   directory with the almond app descriptor (`almond.json`), the only channel `cs` gets it from
    * @param localRepo
    *   local Maven repository with the almond modules, along with their dependencies on Maven
    *   Central snapshots, if `useMavenSnapshots` is true
    * @param kernelVersion
    *   the version of the almond modules in `localRepo`
    * @param launcherDependency
    *   the dependency of the almond launcher in `localRepo` (like `org:name:version`), that the
    *   special kernel runs
    * @param launcherMainClass
    *   the main class of the almond launcher: cs can't find it on its own, as some of its
    *   dependencies have their own `Main-Class`
    */
  final case class KernelSource(
    cs: String,
    appChannel: os.Path,
    localRepo: os.Path,
    useMavenSnapshots: Boolean,
    kernelVersion: String,
    launcherDependency: String,
    launcherMainClass: String
  ) {
    private def localRepoUri =
      localRepo.toNIO.toUri.toASCIIString
        .stripSuffix("/")
    // Spelled as a URL rather than as the central:maven-snapshots alias, as the coursier the
    // kernels embed predates that alias, and ignores COURSIER_REPOSITORIES altogether when it
    // can't parse it
    private def mavenSnapshots = "https://central.sonatype.com/repository/maven-snapshots"

    /** Options making `cs launch` get the almond app descriptor from `appChannel`, and only from
      * there
      */
    def csChannelArgs: Seq[String] =
      Seq(
        "--channel",
        appChannel.toString,
        "--default-channels=false",
        "--file-channels=false"
      )

    /** Repositories passed to `cs launch`, on top of the default ones (JitPack is already added by
      * the almond app descriptor, but not when launching a dependency like the launcher one)
      */
    def csRepositoryArgs: Seq[String] =
      Seq("-r", localRepoUri) ++
        (if (useMavenSnapshots) Seq("-r", mavenSnapshots) else Nil) ++
        Seq("-r", "jitpack")

    /** Repositories the kernels resolve from at runtime, as a `COURSIER_REPOSITORIES` value: the
      * special kernel fetches the actual kernel from them, and all the kernels the dependencies
      * users add
      */
    def kernelRepositories: String =
      (Seq(localRepoUri, "ivy2Local", "central") ++
        (if (useMavenSnapshots) Seq(mavenSnapshots) else Nil)).mkString("|")
  }

  /** Options passed to all the kernels */
  private def kernelOptions = Seq(
    "--log",
    "debug",
    // for the variable inspector JupyterLab extension (installed with the lab dependency group)
    "--variable-inspector",
    "--toree-magics",
    "--silent-imports",
    "--use-notebook-coursier-logger"
  )

  /** Installs a kernel the way the installation pages of the documentation do, with a command like
    * `cs launch --use-bootstrap almond:… -- --install`, letting the kernel ask the `jupyter`
    * command in the `PATH` where to install itself. `JUPYTER_DATA_DIR` is set to `jupyterDir` for
    * the installation, so that the kernel ends up in `jupyterDir/kernels` rather than in the
    * Jupyter directory of the user.
    *
    * @param jupyterScriptsDir
    *   directory with the `jupyter` command, put first in the `PATH` of the installation
    * @param app
    *   what `cs launch` runs, along with its options
    */
  private def installKernel(
    source: KernelSource,
    javaHome: os.Path,
    jupyterScriptsDir: os.Path,
    jupyterDir: os.Path,
    app: Seq[String],
    kernelId: String,
    name: String,
    extraOptions: Seq[String]
  ): Unit = {
    val command = Seq(source.cs, "launch", "--use-bootstrap") ++ app ++ source.csRepositoryArgs ++
      Seq(
        "--",
        "--install",
        // fail rather than install the kernel elsewhere if the jupyter command can't be run
        "--require-jupyter",
        "--id",
        kernelId,
        "--display-name",
        name,
        "--env",
        s"COURSIER_REPOSITORIES=${source.kernelRepositories}"
      ) ++
      kernelOptions ++
      extraOptions
    System.err.println(s"Installing kernel $kernelId")
    os.proc(command).call(
      cwd = jupyterDir,
      env = withPathPrefix(JavaHomes.environment(javaHome), Seq(jupyterScriptsDir)) +
        ("JUPYTER_DATA_DIR" -> jupyterDir.toString),
      stdin = "",
      // kept off our stdout, that `show dev.jupyterCmd…` prints the command to
      stdout = os.ProcessOutput.Readlines(System.err.println),
      stderr = os.Inherit
    )
    if (!os.isFile(jupyterDir / "kernels" / kernelId / "kernel.json"))
      sys.error(s"Kernel $kernelId wasn't installed under ${jupyterDir / "kernels"}")
  }

  /** Environment for Jupyter: the JVM at `javaHome` (for the kernels it starts), the kernel specs
    * under `jupyterDir`, and the commands in `extraPath` (the ACP agents Jupyter AI talks to, …)
    */
  private def jupyterEnvironment(
    javaHome: os.Path,
    jupyterDir: os.Path,
    extraPath: Seq[os.Path] = Nil
  ): Map[String, String] = {
    val javaEnv = withPathPrefix(JavaHomes.environment(javaHome), extraPath)
    javaEnv + ("JUPYTER_PATH" -> jupyterDir.toString)
  }

  /** Puts `dirs` first in the `PATH` of `env`, an environment coming from [[JavaHomes.environment]]
    */
  private def withPathPrefix(env: Map[String, String], dirs: Seq[os.Path]): Map[String, String] =
    if (dirs.isEmpty) env
    else {
      // JavaHomes.environment always sets PATH, possibly spelled differently on Windows
      val (pathKey, pathValue) = env.find(_._1.equalsIgnoreCase("PATH")).get
      val newPathValue =
        (dirs.map(_.toString) :+ pathValue).mkString(File.pathSeparator)
      env + (pathKey -> newPathValue)
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
    System.err.println(s"JAVA_HOME=$javaHome")
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
    )
    val proc = os.proc(
      javaExe,
      "-cp",
      wrapperClassPath.map(_.toString).mkString(File.pathSeparator),
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

  /** Installs one kernel per element of `scalaVersions`, plus the special kernel (the almond
    * launcher, that fetches the kernel for the Scala version a notebook asks for at startup), in
    * `jupyterDir/kernels`, with `cs launch` (see [[installKernel]]), relying on the `jupyter`
    * command of the uv-managed environment with the dependency groups `groups`. Kernels installed
    * there earlier are removed first.
    */
  private def installKernels(
    uv: os.Path,
    workspace: os.Path,
    groups: Seq[String],
    source: KernelSource,
    javaHome: os.Path,
    scalaVersions: Seq[String],
    jupyterDir: os.Path,
    specialExtraOptions: String*
  ): Unit = {
    val scriptsDir = jupyterScriptsDir(uv, workspace, groups)
    os.remove.all(jupyterDir / "kernels")
    os.makeDir.all(jupyterDir / "kernels")
    for (scalaVersion <- scalaVersions)
      installKernel(
        source,
        javaHome,
        scriptsDir,
        jupyterDir,
        Seq(s"almond:${source.kernelVersion}", "--scala", scalaVersion) ++ source.csChannelArgs,
        kernelId(scalaVersion),
        s"Scala $scalaVersion (sources)",
        Nil
      )
    installKernel(
      source,
      javaHome,
      scriptsDir,
      jupyterDir,
      Seq(source.launcherDependency, "--main-class", source.launcherMainClass),
      specialKernelId,
      "Scala (special, sources)",
      specialExtraOptions
    )
    System.err.println(s"JUPYTER_PATH=$jupyterDir")
  }

  /** Installs the kernels, one per element of `scalaVersions` plus the special kernel, and returns
    * the command to run JupyterLab with them.
    *
    * The server also serves the Jupyter Notebook UI (the classic one), under `/tree`. `args` may
    * contain `--base-address=…` and `--classic`, handled here, the rest is passed to JupyterLab.
    *
    * JupyterLab comes with Jupyter AI. Its Claude and Codex personas are enabled if `acpAgentsBin`,
    * added to the `PATH` of JupyterLab, contains the `claude-agent-acp` and `codex-acp` commands
    * (see [[AcpAgents]]).
    *
    * @param scalaVersions
    *   the full Scala versions to install a kernel for
    * @param labExtensions
    *   JupyterLab extensions built from sources to enable, on top of the ones of the Python
    *   environment (see [[LabExtension]])
    */
  def jupyterLabCommand(
    uv: os.Path,
    javaHome: os.Path,
    source: KernelSource,
    scalaVersions: Seq[String],
    jupyterDir: os.Path,
    args: Seq[String],
    workspace: os.Path,
    acpAgentsBin: Option[os.Path],
    labExtensions: Seq[os.Path]
  ): Command = {

    installKernels(
      uv,
      workspace,
      labGroups,
      source,
      javaHome,
      scalaVersions,
      jupyterDir,
      "--quiet=false"
    )

    writeSettingsOverrides(uv, workspace, labGroups)
    LabExtension.install(labExtensions, jupyterDir)

    os.makeDir.all(workspace / "notebooks")
    linkExamples(workspace)
    val (baseAddressOpt, args0) = extractBaseAddress(args)
    val (classic, args1)        = extractClassic(args0)
    val command =
      jupyterCommand(uv, workspace, labGroups, "lab", "--notebook-dir", "notebooks") ++
        baseAddressOpt.toSeq.flatMap(baseAddressOptions) ++
        (if (classic) classicOptions else Nil) ++
        args1
    Command(
      workspace,
      jupyterEnvironment(javaHome, jupyterDir, acpAgentsBin.toSeq),
      command
    )
  }

  /** Starts a JupyterLab server in the background, with one kernel per element of `scalaVersions`
    * plus the special kernel, and returns the files its output goes to
    *
    * @param scalaVersions
    *   the full Scala versions to install a kernel for
    * @param labExtensions
    *   JupyterLab extensions built from sources to enable (see [[LabExtension]])
    */
  def jupyterServer(
    uv: os.Path,
    javaHome: os.Path,
    wrapperClassPath: Seq[os.Path],
    backgroundDir: os.Path,
    source: KernelSource,
    scalaVersions: Seq[String],
    jupyterDir: os.Path,
    args: Seq[String],
    workspace: os.Path,
    acpAgentsBin: Option[os.Path],
    labExtensions: Seq[os.Path]
  ): Seq[os.Path] = {
    val cmd = jupyterLabCommand(
      uv,
      javaHome,
      source,
      scalaVersions,
      jupyterDir,
      args,
      workspace,
      acpAgentsBin,
      labExtensions
    )
    System.err.println(s"JAVA_HOME=$javaHome")
    startBackground(
      javaHome,
      wrapperClassPath,
      backgroundDir,
      cmd.command,
      cmd.cwd,
      cmd.env
    )
  }

  /** Runs a Jupyter console on a kernel running Scala `scalaVersion` */
  def jupyterConsole(
    uv: os.Path,
    javaHome: os.Path,
    source: KernelSource,
    scalaVersion: String,
    jupyterDir: os.Path,
    args: Seq[String],
    workspace: os.Path
  ): Unit = {

    installKernels(uv, workspace, Nil, source, javaHome, Seq(scalaVersion), jupyterDir)

    val command =
      jupyterCommand(uv, workspace, Nil, "console", s"--kernel=${kernelId(scalaVersion)}") ++ args
    runJupyter(command, workspace, jupyterDir, javaHome)
  }
}
