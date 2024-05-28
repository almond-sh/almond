package almond

import java.io.{File, FileOutputStream, PrintStream}

import almond.api.JupyterApi
import almond.channels.zeromq.ZeromqThreads
import almond.directives.KernelOptions
import almond.interpreter.messagehandlers.MessageHandler
import almond.kernel.{Kernel, KernelThreads}
import almond.kernel.install.Install
import almond.launcher.directives.CustomGroup
import almond.logger.{Level, LoggerContext}
import almond.util.ThreadUtil.singleThreadedExecutionContext
import caseapp._
import cats.effect.unsafe.IORuntime
import coursier.cputil.ClassPathUtil

import scala.language.reflectiveCalls
import scala.concurrent.ExecutionContext
import scala.util.Properties

object ScalaKernel extends CaseApp[Options] {

  def run(options: Options, args: RemainingArgs): Unit = {

    coursier.Resolve.proxySetup()

    if (Properties.isWin && System.console() != null && coursier.paths.Util.useJni())
      // Enable ANSI output in Windows terminal
      coursier.jniutils.WindowsAnsiTerminal.enableAnsiOutput()

    def logLevelFromEnv =
      Option(System.getenv("ALMOND_LOG_LEVEL")).flatMap { str =>
        Level.fromString(str) match {
          case Left(err) =>
            Console.err.println(
              s"Error parsing log level from environment variable ALMOND_LOG_LEVEL: $err, ignoring it"
            )
            None
          case other =>
            Some(other)
        }
      }
    val maybeLogLevel = options.log
      .map(Level.fromString)
      .orElse(logLevelFromEnv)
      .getOrElse(Right(Level.Warning))
    val logCtx = maybeLogLevel match {
      case Left(err) =>
        Console.err.println(err)
        sys.exit(1)
      case Right(level) =>
        options.logTo match {
          case None =>
            LoggerContext.stderr(level)
          case Some(f) =>
            LoggerContext.printStream(level, new PrintStream(new FileOutputStream(new File(f))))
        }
    }

    val log = logCtx(getClass)

    if (options.install)
      Install.installOrError(
        defaultId = "scala",
        defaultDisplayName = "Scala",
        language = "scala",
        options = options.installOptions,
        defaultLogoOpt = Option(
          Thread.currentThread()
            .getContextClassLoader
            .getResource("almond/scala-logo-64x64.png")
        ),
        connectionFileArgs = Install.defaultConnectionFileArgs,
        interruptMode =
          if (options.installOptions.interruptViaMessage)
            Some("message")
          else
            None,
        env = options.installOptions.envMap(),
        extraStartupClassPath = options.extraStartupClassPath
      ) match {
        case Left(e) =>
          log.debug("Cannot install kernel", e)
          Console.err.println(s"Error: ${e.getMessage}")
          sys.exit(1)
        case Right(dir) =>
          println(s"Installed scala kernel under $dir")
          sys.exit(0)
      }

    val connectionFile = options.connectionFile.getOrElse {
      Console.err.println(
        "No connection file passed, and installation not asked. Run with --install to install the kernel, " +
          "or pass a connection file via --connection-file to run the kernel."
      )
      sys.exit(1)
    }

    val autoDependencies = options.autoDependencyMap()
    val autoVersions     = options.autoVersionsMap()
    val forceProperties  = options.forceProperties()
    val mavenProfiles    = options.mavenProfiles()
    val extraLinks       = options.extraLinks()
    val predefFiles      = options.predefFiles()

    val initialColors =
      if (options.color) ammonite.util.Colors.Default
      else ammonite.util.Colors.BlackWhite

    if (autoDependencies.nonEmpty)
      log.debug(
        autoDependencies
          .flatMap {
            case (trigger, auto) =>
              Seq("Auto dependency:", s"  Trigger: $trigger") ++ auto.map(dep => s"  Adds: $dep")
          }
          .mkString("\n")
      )

    val interpreterEc               = singleThreadedExecutionContext("scala-interpreter")
    val updateBackgroundVariablesEc = singleThreadedExecutionContext("update-background-variables")

    val zeromqThreads = ZeromqThreads.create("scala-kernel")
    val kernelThreads = KernelThreads.create("scala-kernel")

    val initialClassLoader =
      if (options.specificLoader)
        JupyterApi.getClass.getClassLoader
      else
        Thread.currentThread().getContextClassLoader

    log.debug("Creating interpreter")

    val kernelOptionsFromJson = options.readKernelOptions() match {
      case Some(asJson) =>
        asJson.toKernelOptions match {
          case Left(errors) =>
            log.warn(
              s"Got errors when trying to read options from ${options.kernelOptions.getOrElse("???")}: ${errors.mkString(", ")}"
            )
            KernelOptions()
          case Right(options) =>
            options
        }
      case None =>
        KernelOptions()
    }

    val interpreter = new ScalaInterpreter(
      params = ScalaInterpreterParams(
        updateBackgroundVariablesEcOpt = Some(updateBackgroundVariablesEc),
        extraRepos = options.extraRepository,
        extraBannerOpt = options.banner,
        extraLinks = extraLinks,
        predefCode = options.predefCode,
        predefFiles = predefFiles,
        automaticDependencies = autoDependencies,
        automaticVersions = autoVersions,
        forceMavenProperties = forceProperties,
        mavenProfiles = mavenProfiles,
        codeWrapper = ammonite.compiler.CodeClassWrapper,
        initialColors = initialColors,
        initialClassLoader = initialClassLoader,
        metabrowse = options.metabrowse,
        metabrowseHost = "localhost",
        metabrowsePort = -1,
        lazyInit = true,
        trapOutput = options.trapOutput,
        quiet = options.quiet,
        disableCache = options.disableCache,
        autoUpdateLazyVals = options.autoUpdateLazyVals,
        autoUpdateVars = options.autoUpdateVars,
        useNotebookCoursierLogger = options.useNotebookCoursierLogger,
        silentImports = options.silentImports,
        allowVariableInspector = options.variableInspector,
        useThreadInterrupt = options.useThreadInterrupt,
        outputDir = options.outputDirectory
          .filter(_.trim.nonEmpty)
          .map(os.Path(_, os.pwd))
          .toLeft {
            options.tmpOutputDirectory
              .getOrElse(true) // Create tmp output dir by default
          },
        toreeMagics = options.toreeMagics.orElse(options.toreeCompatibility).getOrElse(false),
        toreeApiCompatibility =
          options.toreeApi.orElse(options.toreeCompatibility).getOrElse(false),
        compileOnly = options.compileOnly,
        extraClassPath = options.extraClassPath
          .filter(_.trim.nonEmpty)
          .flatMap { input =>
            ClassPathUtil.classPath(input)
              .map(os.Path(_, os.pwd))
          },
        initialCellCount = options.initialCellCount.getOrElse(0),
        upfrontKernelOptions = kernelOptionsFromJson,
        ignoreLauncherDirectivesIn = options.ignoreLauncherDirectivesIn.toSet,
        launcherDirectiveGroups = options.launcherDirectiveGroup.map(CustomGroup(_, ""))
      ),
      logCtx = logCtx
    )
    log.debug("Created interpreter")

    // Actually init Ammonite interpreter in background

    val initThread = new Thread("interpreter-init") {
      setDaemon(true)
      override def run() =
        try {
          log.debug("Initializing interpreter (background)")
          interpreter.ammInterp
          log.debug("Initialized interpreter (background)")
        }
        catch {
          case t: Throwable =>
            log.error(s"Caught exception while initializing interpreter, exiting", t)
            sys.exit(1)
        }
    }

    initThread.start()

    val fmtMessageHandler =
      if (options.scalafmt) {
        // thread shuts down after 1 minute of inactivity, automatically re-spawned
        val fmtPool = ExecutionContext.fromExecutorService(
          coursier.cache.internal.ThreadUtil.fixedThreadPool(1)
        )
        val scalafmt = new Scalafmt(
          fmtPool,
          kernelThreads.queueEc,
          logCtx,
          Scalafmt.defaultDialectFor(interpreter.ammInterp.compilerBuilder.scalaVersion)
        )
        scalafmt.messageHandler
      }
      else
        MessageHandler.empty

    log.debug("Running kernel")
    try
      Kernel.create(
        interpreter,
        interpreterEc,
        kernelThreads,
        logCtx,
        fmtMessageHandler,
        options.noExecuteInputFor.map(_.trim).filter(_.nonEmpty).toSet
      )
        .flatMap(_.runOnConnectionFile(
          connectionFile,
          "scala",
          zeromqThreads,
          options.leftoverMessages0(),
          autoClose = true,
          lingerDuration = options.lingerDuration
        ))
        .unsafeRunSync()(IORuntime.global)
    finally
      interpreter.shutdown()
  }

}
