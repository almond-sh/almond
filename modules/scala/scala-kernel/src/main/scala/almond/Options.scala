package almond

import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import almond.api.Properties
import almond.channels.{Channel, Message => RawMessage}
import almond.channels.zeromq.ZeromqThreads
import almond.directives.KernelOptions
import almond.kernel.MessageFile
import almond.kernel.install.{OptionGroups, Options => InstallOptions}
import almond.protocol.KernelInfo
import caseapp._
import caseapp.core.Scala3Helpers._
import caseapp.core.help.Help
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import coursierapi.{Dependency, Module}
import dependency.{AnyDependency, ScalaParameters}
import dependency.api.ops._
import dependency.parser.{DependencyParser, ModuleParser}

import scala.collection.compat._
import scala.concurrent.duration.{Duration, DurationInt}

// The second argument of @HelpMessage is the Markdown documentation of the options, in the
// options page of the website (docs/pages/install-options.md, updated with
// ./mill docs.updateOptionsPage), the first one being shown in the --help output

// format: off
@ProgName("almond")
final case class Options(
  @Group(OptionGroups.installation)
  @HelpMessage("Install the kernel", "Has to be passed to install the kernel.")
    install: Boolean = false,
  @Recurse
    installOptions: InstallOptions = InstallOptions(),
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Maven repository to add when the kernel starts (can be repeated)",
    """Add some Maven repositories right when a kernel starts. (`~/.ivy2/local` and Maven Central are
      |added by default.) Use like
      |```bash
      |--extra-repository https://central.sonatype.com/repository/maven-snapshots
      |--extra-repository https://artifacts.company.com/maven
      |```""".stripMargin
  )
    extraRepository: List[String] = Nil,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "Extra messages to add to the banner of this kernel",
    """Add extra messages to the banner of this kernel (shown via Help > About the Scala Kernel in JupyterLab).
      |Use like
      |```bash
      |--banner "
      |Extra info
      |Some more
      |"
      |```""".stripMargin
  )
    banner: Option[String] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "Entry to add to the Help menu of front-ends, like https://github.com|GitHub (can be repeated)",
    """Add entries to the Help menu of front-ends. Use like
      |```bash
      |--link "https://github.com|GitHub"
      |--link "https://google.com|Google"
      |```""".stripMargin
  )
  @ValueDescription("url|description")
    link: List[String] = Nil,
  @Group(OptionGroups.scala)
  @HelpMessage(
    "Code to run right before the session starts",
    """Run some code right before the session starts. Makes the kernel start fail if the predef
      |doesn't compile or throws an exception. Use like
      |```bash
      |--predef-code "
      |  import scala.jdk.CollectionConverters._
      |"
      |```""".stripMargin
  )
    predefCode: String = "",
  @Group(OptionGroups.scala)
  @HelpMessage(
    "File whose code to run right before the session starts (can be repeated)",
    """Run the code of a file right before the session starts, like `--predef-code` does. Can be
      |repeated. Use like
      |```bash
      |--predef /path/to/predef.sc
      |```""".stripMargin
  )
    predef: List[String] = Nil,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Dependency to add to the user class path before running any user code, like org::name:version (can be repeated)",
    """Add dependencies to the class path of user code when a kernel starts, before predef code
      |and cells are run. Accepts the same dependency syntax as `//> using dep` directives,
      |and can be repeated. Use like
      |```bash
      |--dependency org.typelevel::cats-core:2.12.0
      |--dep com.lihaoyi::os-lib:0.11.4
      |```
      |Dependencies are resolved with the repositories passed via `--extra-repository`.
      |The kernel fails to start if they can't be resolved.""".stripMargin
  )
  @ExtraName("dep")
    dependency: List[String] = Nil,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Dependency to add when a module gets loaded, like org::name=>org::other-name:version (can be repeated)",
    """Automatically add a dependency when a given module gets loaded, like
      |```bash
      |--auto-dependency "org.typelevel::cats-core=>org.typelevel::cats-effect:3.5.4"
      |```
      |Can be repeated. By default, `sh.almond::almond-spark` gets added when Spark is loaded (see
      |[Spark](usage-spark.md)), and `sh.almond::almond-scalapy` when ScalaPy is loaded. Disable these
      |defaults with `--default-auto-dependencies=false`.""".stripMargin
  )
  @ValueDescription("module=>dependency")
    autoDependency: List[String] = Nil,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Version to use for a module when users load it with the _ version, like org::name:version (can be repeated)",
    """Set the version picked for a module when users load it with `_` as version, like
      |`//> using dep org.typelevel::cats-core:_`. Use like
      |```bash
      |--auto-version org.typelevel::cats-core:2.12.0
      |```
      |Can be repeated. By default, the Almond modules, like `sh.almond::almond-spark`, get the
      |version that goes along the kernel. Disable these defaults with `--default-auto-versions=false`.""".stripMargin
  )
  @ValueDescription("module:version")
    autoVersion: List[String] = Nil,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Whether to add almond-spark when Spark is loaded, and almond-scalapy when ScalaPy is loaded",
    """Whether to automatically add `sh.almond::almond-spark` when Spark is loaded, and
      |`sh.almond::almond-scalapy` when ScalaPy is loaded (default: true). See `--auto-dependency`.""".stripMargin
  )
    defaultAutoDependencies: Boolean = true,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Whether to pick the versions of the Almond modules loaded with the _ version",
    """Whether to pick the version of the Almond modules, like `sh.almond::almond-spark`,
      |when users load them with `_` as version (default: true). See `--auto-version`.""".stripMargin
  )
    defaultAutoVersions: Boolean = true,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Default almond-spark version to load when Spark is loaded as a library",
    "Version of `sh.almond::almond-spark` to add when Spark is loaded (see `--default-auto-dependencies`). Defaults to the one that goes along the kernel."
  )
    defaultAlmondSparkVersion: Option[String] = None,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Default almond-scalapy version to load when ScalaPy is loaded as a library",
    "Version of `sh.almond::almond-scalapy` to add when ScalaPy is loaded (see `--default-auto-dependencies`). Defaults to the one that goes along the kernel."
  )
    defaultAlmondScalapyVersion: Option[String] = None,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Force Maven properties during dependency resolution",
    """Force a Maven property during dependency resolution, like `--force-property key=value`. Can be
      |repeated.""".stripMargin
  )
  @ValueDescription("key=value")
    forceProperty: List[String] = Nil,
  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Enable Maven profile (start with ! to disable)",
    """Enable a Maven profile during dependency resolution, like `--profile name`, or disable it, like
      |`--profile '!name'`. Can be repeated.""".stripMargin
  )
    profile: List[String] = Nil,
  @Group(OptionGroups.internal)
  @HelpMessage(
    "Log level (one of none, error, warn, info, debug)",
    """Sets the kernel log level. Can be any of
      |- `none`,
      |- `error`,
      |- `warn` (default),
      |- `info`,
      |- `debug`.""".stripMargin
  )
  @ValueDescription("none|error|warn|info|debug")
    log: Option[String] = None,
  @Group(OptionGroups.internal)
  @HelpMessage(
    "Send log to a file rather than stderr",
    """Write the kernel logs to a file, rather than to the standard error, like
      |```bash
      |--log debug --log-to /tmp/almond.log
      |```""".stripMargin
  )
  @ValueDescription("/path/to/log-file")
    logTo: Option[String] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "Path to the connection file Jupyter passes to the kernel",
    """Path to the connection file that Jupyter passes to the kernel when starting it. This is
      |written in the kernel spec upon installation, and doesn't need to be passed by hand.""".stripMargin
  )
    connectionFile: Option[String] = None,
  // For class loader isolation, the user code is loaded from the classloader of the api module.
  // If the right -i / -I options are passed to coursier bootstrap when generating a launcher, that loader
  // only sees the api module and its dependencies, rather than the full classpath of almond.
  @Group(OptionGroups.internal)
  @HelpMessage(
    "Use class loader that loaded the api module rather than the context class loader",
    """Whether to load user code from the class loader of the user-facing modules of Almond, rather
      |than from the context class loader (default: true). See
      |[Isolation of Almond internal dependencies](install-advanced.md#isolation-of-almond-internal-dependencies).""".stripMargin
  )
    specificLoader: Boolean = true,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "Kept for compatibility, currently has no effect",
    "Kept for compatibility, currently has no effect."
  )
    metabrowse: Boolean = false,
  @Group(OptionGroups.internal)
  @HelpMessage(
    "Trap what user code sends to stdout and stderr",
    """Don't send what user code prints to the standard output and error to the front-end. It
      |goes to the standard output and error of the kernel process instead.""".stripMargin
  )
    trapOutput: Boolean = false,
  @Group(OptionGroups.internal)
  @HelpMessage(
    "If false, duplicates stdout/stderr to console, similar to IPKernelApp.quiet",
    """Whether not to copy what user code prints to the standard output and error of the kernel
      |process (default: true). What user code prints is sent to the front-end either way.""".stripMargin
  )
    quiet: Boolean = true,
  @Group(OptionGroups.scala)
  @HelpMessage("Disable ammonite compilation cache", "Disable the compilation cache of Ammonite.")
    disableCache: Boolean = false,
  @Group(OptionGroups.scala)
  @HelpMessage(
    "Whether to automatically update lazy val-s upon computation",
    """Whether the output of top-level `lazy val`s should be updated when they're computed (default: true).
      |See [Auto-updated variables](api-jupyter.md#auto-updated-variables).""".stripMargin
  )
    autoUpdateLazyVals: Boolean = true,
  @Group(OptionGroups.scala)
  @HelpMessage(
    "Whether to automatically update var-s upon change",
    """Whether the output of top-level `var`s should be updated when they change later on (default: true).
      |See [Auto-updated variables](api-jupyter.md#auto-updated-variables). Disable with
      |```bash
      |--auto-update-vars=false
      |```""".stripMargin
  )
    autoUpdateVars: Boolean = true,
  @Group(OptionGroups.scala)
  @HelpMessage(
    "Whether to silence imports (not printing them back in output)",
    """Suppress the automatic output for `import` statements. Disabled by default.
      |Enable it when installing the kernel:
      |
      |```bash
      |cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --install --silent-imports
      |```
      |
      |Add `--force` when replacing an existing kernel installation.
      |
      |To limit automatic output for values as well, use `--output-style`.""".stripMargin
  )
    silentImports: Boolean = false,
  @Group(OptionGroups.scala)
  @HelpMessage(
    "How cell results are displayed: default (each value, with its name and type), last (only the last value, with its name and type), or python (like the Python kernel, only the value of the last expression) - can be changed mid-session with '//> using outputStyle default|last|python'",
    """Change how the results of cells are displayed, for example to reduce the output
      |chatter described in [issue #256](https://github.com/almond-sh/almond/issues/256).
      |Accepts:
      |- `default`: display each value defined or computed in a cell, along with its name
      |  and type (`a: Int = 1`)
      |- `last`: only display the last value defined or computed in a cell, along with its
      |  name and type. Expressions (not assigned to a `val`) with a rich display, such as
      |  `Markdown("…")` or `Html("…")`, are still shown
      |- `python`: display results like the Python kernel does, that is only display the
      |  value of the last expression of a cell, without its name and type
      |
      |Enable it when installing the kernel:
      |
      |```bash
      |cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --install --output-style python
      |```
      |
      |The [newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher)
      |also accepts this option directly:
      |
      |```bash
      |cs launch --use-bootstrap sh.almond::launcher:@VERSION@ -- --install --output-style python
      |```
      |
      |Add `--force` when replacing an existing kernel installation, then start a new
      |kernel session.
      |
      |The output style can also be changed at any point in a session, for the cells that
      |follow, with a using directive:
      |
      |```scala
      |//> using outputStyle python
      |```
      |
      |With any output style, all statements still execute, and the variables they define
      |remain available in subsequent cells. Explicit output, such as `println` and
      |`display()` calls, is still shown.
      |
      |`--last-value-only`, kept for compatibility, is the same as `--output-style last`.
      |
      |##### `last`
      |
      |For example, this cell displays only `second: Int = 2`:
      |
      |```scala
      |val first = 1
      |val second = first + 1
      |```
      |
      |Import statements and class, method, and type definitions are not echoed. Trailing
      |imports or definitions do not replace the last value. If the last value has type
      |`Unit`, no automatic result is displayed. Rich values such as `almond.display.Html`
      |are displayed automatically only when they are the last value in the cell.
      |
      |##### `python`
      |
      |For example, this cell displays nothing:
      |
      |```scala
      |val records = (0 until 10).map(i => (i, i * i))
      |val evenRecords = records.filter(_._1 % 2 == 0)
      |```
      |
      |and this one only displays `Vector((0, 0), (2, 4))`:
      |
      |```scala
      |evenRecords.take(2)
      |```
      |
      |Nothing is displayed for `val` definitions, imports, or class, method, and type
      |definitions, nor for expressions that are not the last statement of a cell. Like
      |in the Python kernel, the value of the last expression isn't displayed either if
      |the cell ends with a semicolon:
      |
      |```scala
      |evenRecords.take(2);
      |```
      |
      |If the last expression has type `Unit`, nothing is displayed. Rich values such as
      |`almond.display.Html` are displayed automatically when they are the last
      |expression of the cell.""".stripMargin
  )
  @ValueDescription("default|last|python")
    outputStyle: Option[String] = None,
  @HelpMessage("Kept for compatibility, same as --output-style last")
  @Hidden
    lastValueOnly: Boolean = false,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "Whether to use a notebook-specific coursier logger",
    "Show the progress of dependency downloads in the notebook, rather than in the kernel logs."
  )
    useNotebookCoursierLogger: Boolean = false,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "Whether to enable variable inspector",
    """Whether to enable support for the
      |[variable inspector](https://github.com/jupyterlab-contrib/jupyterlab-variableInspector)
      |JupyterLab extension. By default, it gets enabled when the extension asks for it. Disable it with
      |`--variable-inspector=false`.""".stripMargin
  )
    variableInspector: Option[Boolean] = None,
  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "Whether to process format requests with scalafmt",
    """Whether to format cells with [scalafmt](https://scalameta.org/scalafmt) when front-ends ask for it,
      |like the [almond-scalafmt](https://github.com/almond-sh/almond-scalafmt) JupyterLab extension does
      |(default: true).""".stripMargin
  )
    scalafmt: Boolean = true,
  @Group(OptionGroups.internal)
  @HelpMessage(
    "Whether to use 'Thread.interrupt' method or deprecated 'Thread.stop' method (default) when interrupting kernel.",
    """Whether to stop running cells with `Thread.interrupt`, rather than with the deprecated
      |`Thread.stop` (default) when interrupting the kernel. As `Thread.stop` isn't supported
      |any more from JDK 20 on, interrupt messages (see [`--interrupt-via-message`](#--interrupt-via-message))
      |always use `Thread.interrupt` on these JDKs. Note that `Thread.interrupt` only stops code
      |that checks for interruptions, like `Thread.sleep` or blocking I/O calls.""".stripMargin
  )
    useThreadInterrupt: Boolean = false,

  @Group(OptionGroups.internal)
  @HelpMessage(
    "Directory to write the class files compiled from cells to",
    "Directory to write the class files compiled from cells to, rather than to a temporary directory. See `--tmp-output-directory`."
  )
  @ExtraName("outputDir")
    outputDirectory: Option[String] = None,
  @Group(OptionGroups.internal)
  @HelpMessage(
    "Whether to write the class files compiled from cells to a temporary directory, rather than keeping them in memory only (default: true)",
    """Whether to write the class files compiled from cells to a temporary directory, rather than
      |keeping them in memory only (default: true). Ignored if `--output-directory` is passed.""".stripMargin
  )
  @ExtraName("tmpOutputDir")
    tmpOutputDirectory: Option[Boolean] = None,

  @Group(OptionGroups.toree)
  @HelpMessage(
    "Add experimental support for Toree magics",
    """Enable experimental support for [Toree](https://toree.apache.org) magics.
      |
      |Simple line magics such as `%AddDeps` (always assumed to be transitive as of writing this, `--transitive` is just ignored),
      |`%AddJar`, and cell magics such as `%%html` or `%%javascript` are supported.
      |See [Enabling Toree compatibility](install-advanced.md#enabling-toree-compatibility) for more details.""".stripMargin
  )
    toreeMagics: Option[Boolean] = None,
  @Group(OptionGroups.toree)
  @HelpMessage(
    "Add experimental support for Toree API compatibility",
    "Enable basic support for the Toree API. See [Toree API](install-advanced.md#toree-api)."
  )
    toreeApi: Option[Boolean] = None,
  @Group(OptionGroups.toree)
  @HelpMessage(
    "Add experimental support for Toree compatibility (magics and API)",
    "Same as `--toree-magics` and `--toree-api` together."
  )
    toreeCompatibility: Option[Boolean] = None,

  @Group(OptionGroups.scala)
  @HelpMessage(
    "Enable or disable color cell output upon startup (enabled by default, pass --color=false to disable)",
    """Whether to color the results of cells, like `n: Int = 2` (default: true). Disable with
      |```bash
      |--color=false
      |```""".stripMargin
  )
    color: Boolean = true,

  @Group(OptionGroups.scala)
  @HelpMessage("Enable compile-only mode", "Only compile the code of cells, without running it.")
    compileOnly: Boolean = false,

  @Group(OptionGroups.dependency)
  @HelpMessage(
    "Extra class path to add upfront in the session - should accept the same format as 'java -cp', see https://github.com/coursier/class-path-util for more details - add the source JARs of its JARs too, for inspections to show their documentation",
    """Add JARs or directories of class files to the class path of user code when a kernel starts.
      |Accepts the same format as `java -cp`. Use like
      |```bash
      |--extra-class-path /path/to/lib.jar:/path/to/other-lib.jar
      |```
      |[Inspections](usage-completion.md#inspections) show the documentation of the classes
      |of these JARs only if their source JARs are passed too, like
      |```bash
      |--extra-class-path /path/to/lib.jar:/path/to/lib-sources.jar
      |```""".stripMargin
  )
  @ExtraName("extraCp")
  @ExtraName("extraClasspath")
    extraClassPath: List[String] = Nil,

  @Group(OptionGroups.installation)
  @HelpMessage(
    "Extra class path to add alongside the kernel JAR upon installation - should accept the same format as 'java -cp', see https://github.com/coursier/class-path-util for more details - only taken into account when --install is specified",
    """JARs to add to the class path of `java` itself, alongside the kernel JARs, when installing
      |the kernel (see [Custom URL protocol support](install-advanced.md#custom-url-protocol-support)).
      |Accepts the same format as `java -cp`. Only taken into account along with `--install`.""".stripMargin
  )
  @ExtraName("extraStartupClasspath")
    extraStartupClassPath: List[String] = Nil,

  @HelpMessage("JSON file containing messages to handle before those originating from the ZeroMQ sockets")
  @Hidden
    leftoverMessages: Option[String] = None,

  @HelpMessage("JSON file telling how to connect to the launcher that started this kernel, used instead of a connection file")
  @Hidden
    relayFile: Option[String] = None,

  @HelpMessage("Bind the ports of the connection file with SO_REUSEPORT, along the placeholder sockets of the launcher that exec-ed this kernel")
  @Hidden
    reusePort: Boolean = false,

  @HelpMessage("Delete these files when exiting")
  @Hidden
    deleteOnExit: List[String] = Nil,

  @HelpMessage("Number of cells run before starting this kernel")
  @Hidden
    initialCellCount: Option[Int] = None,

  @HelpMessage("Do not send execute_input message for incoming messages with the passed ids")
  @Hidden
    noExecuteInputFor: List[String] = Nil,

  @HelpMessage("Session id to use in the headers of the messages sent by the kernel (random if not specified)")
  @Hidden
    kernelSessionId: Option[String] = None,

  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "User name to use in the headers of the messages sent by the kernel (default: name of the user running the kernel)",
    """Set the user name in the headers of the messages sent by the kernel. Defaults to
      |the name of the user running the kernel. Pass it along `--install` to write it in the kernel spec, like
      |```bash
      |--install --username jupyter
      |```""".stripMargin
  )
    username: Option[String] = None,

  @HelpMessage("Path of JSON file with using directives kernel options")
  @Hidden
    kernelOptions: Option[String] = None,

  @HelpMessage("Do warn users about launcher directives for incoming messages with the passed ids")
  @Hidden
    ignoreLauncherDirectivesIn: List[String] = Nil,

  @HelpMessage("Pass launcher directive groups with this option. These directives will be either ignored (see --ignore-launcher-directives-in), or trigger an unused directive warning")
  @Hidden
    launcherDirectiveGroup: List[String] = Nil,

  @HelpMessage("""Time given to the client to accept ZeroMQ messages before exiting. Parsed with scala.concurrent.duration.Duration, this accepts things like "Inf" or "5 seconds"""")
  @Hidden
    linger: Option[String] = None,

  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "If zero-d ports are passed by Jupyter in connection file, bind to random available ports, " +
    "and update the connection file with the actually used ports",
    """Whether to bind to random available ports, and write them back in the connection file, if
      |Jupyter passes zero ports in it (default: true).""".stripMargin
  )
    bindToRandomPorts: Option[Boolean] = None,

  @Group(OptionGroups.jupyter)
  @HelpMessage(
    "Maximum size of the ZeroMQ messages accepted by the kernel, like 500m, 2g, or 1048576 (in bytes), or none for no limit (default: 500m). Peers sending larger messages get disconnected.",
    """Maximum size of the ZeroMQ messages accepted by the kernel, `500m` by default. Peers sending
      |larger messages get disconnected. Accepts sizes in bytes, optionally with a `k`, `m`, or `g` suffix
      |(powers of 1024), or `none` to accept messages of any size. Pass it along `--install` to write it
      |in the kernel spec, like
      |```bash
      |--install --max-message-size 2g
      |```""".stripMargin
  )
  @ValueDescription("size|none")
    maxMessageSize: Option[String] = None,

  @HelpMessage("Class name to use to wrap user code - wrapping classes will be this name with an integer index appended")
  @Hidden
    wrapperName: Option[String] = None,

  @HelpMessage("Package name where user code should be compiled - defaults to ammonite.$sess")
  @Hidden
    pkgName: Option[String] = None,

  @HelpMessage("Log raw code about to be compiled (after post-processing, raw code passed to the compiler)")
  @Hidden
    logCode: Boolean = false
) {
  // format: on

  private lazy val sbv = {
    val sv = almond.internal.Constants.actualScalaVersion
    if (sv.startsWith("2."))
      sv.split('.').take(2).mkString(".")
    else
      sv.split('.').head
  }

  private lazy val scalaParams =
    ScalaParameters(scala.util.Properties.versionNumberString)

  /** The configuration of an inline-configuration dependency (like `org:name:version:config`).
    *
    * `toCs` doesn't carry it over to the coursier-interface dependency, so we read it from the user
    * params the parser puts it in, and apply it ourselves.
    */
  private def inlineConfiguration(dep: AnyDependency): Option[String] =
    dep.userParamsMap.get("$inlineConfiguration").flatMap(_.flatten.headOption)

  private lazy val ammSparkVersion = defaultAlmondSparkVersion
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse(Properties.ammoniteSparkVersion)
  private lazy val almondScalapyVersion = defaultAlmondScalapyVersion
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse(Properties.almondScalapyVersion)

  def autoDependencyMap(): Map[Module, Seq[Dependency]] = {

    val default =
      if (defaultAutoDependencies)
        Map(
          Module.of("org.apache.spark", "*") -> Seq(Dependency.of(
            Module.of("sh.almond", s"almond-spark_$sbv"),
            ammSparkVersion
          )),
          Module.of("me.shadaj", "scalapy*") -> Seq(Dependency.of(
            Module.of("sh.almond", s"almond-scalapy_$sbv"),
            almondScalapyVersion
          )),
          Module.of("dev.scalapy", "scalapy*") -> Seq(Dependency.of(
            Module.of("sh.almond", s"almond-scalapy_$sbv"),
            almondScalapyVersion
          ))
        )
      else
        Map.empty[Module, Seq[Dependency]]

    val fromArgs = autoDependency
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { s =>
        s.split("=>") match {
          case Array(trigger, auto) =>
            val trigger0 = ModuleParser.parse(trigger) match {
              case Left(err) =>
                sys.error(s"Malformed module '$trigger' in --auto-dependency argument '$s': $err")
              case Right(m) =>
                m.applyParams(scalaParams).toCs
            }
            val auto0 = DependencyParser.parse(auto) match {
              case Left(err) =>
                sys.error(s"Malformed dependency '$auto' in --auto-dependency argument '$s': $err")
              case Right(d) =>
                val dep0 = d.applyParams(scalaParams)
                val dep  = dep0.toCs
                inlineConfiguration(dep0).fold(dep)(dep.withConfiguration)
            }
            trigger0 -> auto0
          case _ =>
            sys.error(s"Unrecognized --auto-dependency argument: $s")
        }
      }
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap

    default ++ fromArgs
  }

  def autoVersionsMap(): Map[Module, String] = {

    val default =
      if (defaultAutoVersions)
        Map(
          Module.of("sh.almond", s"almond-spark_$sbv")     -> Properties.ammoniteSparkVersion,
          Module.of("sh.almond", s"ammonite-spark_$sbv")   -> Properties.ammoniteSparkVersion,
          Module.of("sh.almond", s"json-api-jackson_$sbv") -> Properties.jsonApiJacksonVersion,
          Module.of("sh.almond", s"almond-scalapy_$sbv")   -> almondScalapyVersion,
          // the other almond modules, published along with us
          Module.of("sh.almond", "*") -> Properties.version
        )
      else
        Map.empty[Module, String]

    val fromArgs = autoVersion
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { s =>
        val idx = s.lastIndexOf(':')
        if (idx < 0)
          sys.error(s"Malformed --auto-version argument '$s'")
        else {
          val before = s.substring(0, idx)
          val ver    = s.substring(idx + 1)
          val mod = ModuleParser.parse(before) match {
            case Left(err) =>
              sys.error(s"Malformed module '$before' in --auto-version argument '$s': $err")
            case Right(m) =>
              m.applyParams(scalaParams).toCs
          }

          mod -> ver
        }
      }
      .toMap

    default ++ fromArgs
  }

  def forceProperties(): Map[String, String] =
    forceProperty
      .filter(_.nonEmpty)
      .map(_.split("=", 2))
      .map {
        case Array(k, v) =>
          k -> v
        case other =>
          sys.error(s"Malformed link: $other")
      }
      .toMap

  def mavenProfiles(): Map[String, Boolean] =
    profile
      .filter(_.nonEmpty)
      .map { p =>
        if (p.startsWith("!"))
          p.stripPrefix("!") -> false
        else
          p -> true
      }
      .toMap

  def extraLinks(): Seq[KernelInfo.Link] =
    link
      .map(_.split(Pattern.quote("|"), 2))
      .map {
        case Array(url, desc) =>
          KernelInfo.Link(desc, url)
        case other =>
          sys.error(s"Malformed link: $other")
      }

  def predefFiles(): Seq[Path] =
    predef.map { p =>
      val path = Paths.get(p)
      if (!Files.exists(path)) {
        System.err.println(s"Error: predef $p: not found")
        sys.exit(1)
      }
      if (!Files.isRegularFile(path)) {
        System.err.println(s"Error: predef $p: not a file")
        sys.exit(1)
      }
      path
    }

  def outputStyle0(): OutputStyle =
    outputStyle.map(_.trim).filter(_.nonEmpty) match {
      case Some(input) =>
        OutputStyle.parse(input) match {
          case Left(err) =>
            System.err.println(s"Error: $err")
            sys.exit(1)
          case Right(style) => style
        }
      case None =>
        if (lastValueOnly) OutputStyle.Last
        else OutputStyle.Default
    }

  def maxMessageSize0(): Option[Long] =
    maxMessageSize.map(_.trim).filter(_.nonEmpty) match {
      case Some(input) =>
        ZeromqThreads.parseMaxMessageSize(input) match {
          case Left(err) =>
            System.err.println(s"Error: $err")
            sys.exit(1)
          case Right(sizeOpt) => sizeOpt
        }
      case None =>
        Some(ZeromqThreads.defaultMaxMessageSize)
    }

  def dependencies(): Seq[AnyDependency] =
    dependency
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { input =>
        DependencyParser.parse(input) match {
          case Left(err) =>
            System.err.println(s"Error: malformed dependency '$input': $err")
            sys.exit(1)
          case Right(dep) => dep
        }
      }

  def leftoverMessages0(): Seq[(Channel, RawMessage)] =
    leftoverMessages.toSeq.flatMap { strPath =>
      val path    = os.Path(strPath, os.pwd)
      val bytes   = os.read.bytes(path)
      val msgFile = readFromArray(bytes)(MessageFile.codec)
      msgFile.parsedMessages
    }

  def readKernelOptions(): Option[KernelOptions.AsJson] =
    kernelOptions.map { strPath =>
      val path  = os.Path(strPath, os.pwd)
      val bytes = os.read.bytes(path)
      readFromArray(bytes)(KernelOptions.AsJson.codec)
    }

  lazy val lingerDuration = linger
    .map(_.trim)
    .filter(_.nonEmpty)
    .map(Duration(_))
    .getOrElse(5.seconds)
}

object Options {

  implicit lazy val parser: Parser[Options] = Parser.derive
  implicit lazy val help: Help[Options] = Help.derive
    // not sure why the @ProgName annotation above isn't picked here
    .withProgName("almond")

}
