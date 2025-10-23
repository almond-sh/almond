package almond

import almond.api.JupyterApi
import almond.channels.zeromq.ZeromqThreads
import almond.directives.KernelOptions
import almond.interpreter.messagehandlers.MessageHandler
import almond.kernel.{Kernel, KernelThreads}
import almond.kernel.install.Install
import almond.launcher.directives.CustomGroup
import almond.logger.{Level, LoggerContext}
import almond.util.ThreadUtil
import caseapp._
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import coursier.cputil.ClassPathUtil

import java.io.{File, FileOutputStream, PrintStream}
import java.nio.file.Paths
import java.util.Scanner
import java.util.concurrent.atomic.AtomicInteger

import scala.language.reflectiveCalls
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Properties

object ScalaKernel extends CaseApp[Options] {

  private lazy val scanner = new Scanner(System.in)
  private def pause(): Unit = {
    System.err.println("Press enter to continue")
    scanner.nextLine()
  }

  def run(options: Options, args: RemainingArgs): Unit = {

    // should make scalac manage opened JARs more carefully
    sys.props("scala.classpath.closeZip") = "true"

    if (!options.install && options.pause)
      pause()

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

    val connectionFiles = {
      val args =
        options.connectionFile.flatMap(_.split(File.pathSeparator)).filter(_.trim.nonEmpty).distinct
      if (options.debugMultiKernels)
        args
      else if (args.length > 1) {
        Console.err.println(
          "Multiple connection files not allowed. " +
            "Pass --debug-multi-kernels to allow that if you really know what you're doing."
        )
        sys.exit(1)
      }
      else
        args.headOption.map(Seq(_)).getOrElse {
          Console.err.println(
            "No connection file passed, and installation not asked. Run with --install to install the kernel, " +
              "or pass a connection file via --connection-file to run the kernel."
          )
          sys.exit(1)
        }
    }

    log.debug(s"connectionFiles=${pprint.apply(connectionFiles)}")

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
          .mkString(System.lineSeparator())
      )

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

    val connectionFilesAndThreads =
      if (options.debugMultiKernelsSameThreads) {
        val threads = ScalaKernelThreads.create("scala-kernel")
        connectionFiles.map((_, threads))
      }
      else
        connectionFiles.zipWithIndex.map {
          case (connectionFile, idx) =>
            val threads = ScalaKernelThreads.create(s"scala-kernel-$idx")
            (connectionFile, threads)
        }

    val tasks = connectionFilesAndThreads.zipWithIndex.iterator.map {
      case ((connectionFile, threads), idx) =>
        val interpreter = new ScalaInterpreter(
          params = ScalaInterpreterParams(
            updateBackgroundVariablesEcOpt = Some(threads.updateBackgroundVariablesEc),
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
            launcherDirectiveGroups = options.launcherDirectiveGroup.map(CustomGroup(_, "")),
            wrapperNamePrefix = options.wrapperName
              .map(_.trim)
              .filter(_.nonEmpty)
              .getOrElse(ScalaInterpreterParams.defaultWrapperNamePrefix)
          ),
          logCtx = logCtx
        )
        log.debug("Created interpreter")

        // Actually init Ammonite interpreter in background

        val initThread = new Thread(s"interpreter-init-$idx") {
          setDaemon(true)
          override def run() =
            try {
              log.debug(s"Initializing interpreter $idx (background)")
              interpreter.ammInterp
              log.debug(s"Initialized interpreter $idx (background)")
            }
            catch {
              case t: Throwable =>
                log.error(s"Caught exception while initializing interpreter $idx, exiting", t)
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
              threads.kernelThreads.queueEc,
              logCtx,
              Scalafmt.defaultDialectFor(interpreter.ammInterp.compilerBuilder.scalaVersion)
            )
            scalafmt.messageHandler
          }
          else
            MessageHandler.empty

        log.debug(s"Running kernel $idx")
        val task =
          Kernel.create(
            interpreter,
            threads.interpreterEc,
            threads.kernelThreads,
            logCtx,
            fmtMessageHandler,
            options.noExecuteInputFor.map(_.trim).filter(_.nonEmpty).toSet
          )
            .flatMap(_.runOnConnectionFile(
              connectionFile,
              "scala",
              threads.zeromqThreads,
              options.leftoverMessages0(),
              autoClose = true,
              lingerDuration = options.lingerDuration,
              bindToRandomPorts =
                if (options.bindToRandomPorts.getOrElse(true)) Some(Paths.get(connectionFile))
                else None
            ))

        task.bracket(_ => IO.unit)(_ =>
          IO {
            log.debug(s"Shutting down interpreter $idx")
            interpreter.shutdown()
            log.debug(s"Shut down interpreter $idx")
            if (!options.debugMultiKernelsSameThreads) {
              log.debug(s"Shutting down threads $idx")
              threads.close()
              log.debug(s"Shut down threads $idx")
            }
          }
        )
    }

    val helperEc       = ThreadUtil.singleThreadedExecutionContextExecutorService("helper")
    val processRuntime = IORuntime.builder().build()
    try {
      val done  = Promise[Unit]()
      val count = new AtomicInteger(connectionFilesAndThreads.length)
      for (task <- tasks)
        task.unsafeToFuture()(processRuntime).onComplete { res =>
          for (err <- res.failed)
            err.printStackTrace(System.err)
          if (count.decrementAndGet() <= 0)
            done.success(())
        }(helperEc)
      Await.result(done.future, Duration.Inf)
    }
    finally {
      processRuntime.shutdown()
      helperEc.shutdown()
    }

    log.debug("Kernel finally")
    if (options.debugMultiKernelsSameThreads)
      connectionFilesAndThreads.map(_._2).distinct.foreach(_.close())
    log.debug("Kernel finally post-threads-close")
    if (options.pause) {
      System.err.println("Before pause")
      pause()
    }
  }

}
