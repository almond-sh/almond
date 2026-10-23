package almondbuild

/** JupyterLab extensions built from sources, for the JupyterLab servers of the dev.jupyter*
  * commands. These are prebuilt (federated) extensions, like the ones generated from the JupyterLab
  * 4 extension template: their `package.json` has a `jupyterlab.outputDir` field, that `jlpm build`
  * writes the built extension to.
  *
  * Extensions are built in a directory of the build, where their sources are copied, rather than in
  * their source directory. The built extensions are then copied under the `labextensions` directory
  * of a `JUPYTER_PATH` entry, where JupyterLab and the Jupyter Notebook UI pick them up, without
  * having to install anything in the Python environment.
  */
object LabExtension {

  /** Environment variable pointing at the sources of the almond-scalafmt JupyterLab extension
    * (absolute path, or relative to the workspace), see
    * https://github.com/almond-sh/almond-scalafmt
    */
  def scalafmtEnvVar = "ALMOND_SCALAFMT_EXTENSION"

  /** Top-level files and directories not copied from the sources of extensions: things built or
    * downloaded there, and things not needed to build them
    */
  private val excluded = Set(
    ".git",
    ".yarn",
    "node_modules",
    "lib",
    "coverage",
    "ui-tests",
    ".eslintcache",
    ".stylelintcache",
    "tsconfig.tsbuildinfo"
  )

  /** Directories kept in the build directory of extensions across builds: the dependencies, so that
    * they're not downloaded again upon source changes
    */
  private val keptDirs = Set(".yarn", "node_modules")

  /** The directory the extension in `dir` gets built to, relative to `dir`, from the
    * `jupyterlab.outputDir` field of its `package.json`
    */
  def outputDir(dir: os.Path): os.SubPath = {
    val packageJson = dir / "package.json"
    if (!os.isFile(packageJson))
      sys.error(
        s"No package.json found in $dir, " +
          "is it a JupyterLab extension?"
      )
    ujson.read(os.read(packageJson)).obj.get("jupyterlab")
      .flatMap(_.obj.get("outputDir"))
      .map(value => os.SubPath(value.str.stripPrefix("./")))
      .getOrElse {
        sys.error(
          s"No jupyterlab.outputDir field in $packageJson: " +
            "only prebuilt extensions, like the ones of the JupyterLab 4 extension template, " +
            "are supported"
        )
      }
  }

  /** The source files of the extension in `dir` */
  def sources(dir: os.Path): Seq[os.Path] = {
    val outputDir0 = dir / outputDir(dir)
    os.walk(
      dir,
      skip = p =>
        p == outputDir0 ||
        (p / os.up == dir && excluded(p.last)) ||
        p.last == "node_modules" ||
        p.last == ".ipynb_checkpoints"
    ).filter(os.isFile(_))
  }

  /** Builds the extension whose sources are `sources`, under `sourceDir`, in `dest`, and returns
    * the built extension (its `outputDir`, under `dest`)
    *
    * @param uvRun
    *   the command running a command from the Python environment of JupyterLab, that provides the
    *   `jlpm` command (Node.js needs to be on the `PATH` too)
    */
  def build(
    uvRun: Seq[String],
    sourceDir: os.Path,
    sources: Seq[os.Path],
    dest: os.Path
  ): os.Path = {
    val workDir = dest / "sources"
    // start from a clean directory but for the dependencies, so that removed source files or
    // stale TypeScript build info don't stick around
    if (os.exists(workDir))
      for (p <- os.list(workDir) if !keptDirs(p.last))
        os.remove.all(p)
    for (f <- sources)
      os.copy(f, workDir / f.subRelativeTo(sourceDir), createFolders = true)

    val name = ujson.read(os.read(workDir / "package.json"))("name").str
    def jlpm(args: String*): Unit =
      os.proc(uvRun, "jlpm", args).call(
        cwd = workDir,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    System.err.println(
      s"Building the $name JupyterLab extension from $sourceDir"
    )
    jlpm("install")
    jlpm("run", "build")

    val output = workDir / outputDir(workDir)
    if (!os.isFile(output / "package.json"))
      sys.error(
        s"Building $name didn't write ${output / "package.json"}"
      )
    output
  }

  /** Copies the built extensions `extensions` under the `labextensions` directory of `jupyterDir`,
    * a `JUPYTER_PATH` entry, removing any extension that was copied there before
    */
  def install(extensions: Seq[os.Path], jupyterDir: os.Path): Unit = {
    val dir = jupyterDir / "labextensions"
    os.remove.all(dir)
    for (ext <- extensions) {
      val name = ujson.read(os.read(ext / "package.json"))("name").str
      os.copy(ext, dir / os.SubPath(name), createFolders = true)
      System.err.println(s"Enabled the $name JupyterLab extension built from sources")
    }
  }
}
