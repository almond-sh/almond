package almond

import java.io.{File, FileOutputStream, PrintStream}

import almond.api.JupyterApi
import almond.channels.zeromq.ZeromqThreads
import almond.kernel.{Kernel, KernelThreads}
import almond.kernel.install.Install
import almond.logger.{Level, LoggerContext}
import almond.util.ThreadUtil.singleThreadedExecutionContext
import caseapp._

import scala.language.reflectiveCalls

object ScalaKernel extends CaseApp[Options] {

  def run(options: Options, args: RemainingArgs): Unit = {

    val logCtx = Level.fromString(options.log) match {
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
        interruptMode = {
          if (options.installOptions.interruptViaMessage)
            Some("message")
          else
            None
        }
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
    val forceProperties = options.forceProperties()
    val mavenProfiles = options.mavenProfiles()
    val extraLinks = options.extraLinks()
    val predefFiles = options.predefFiles()


    log.info(
      autoDependencies
        .flatMap {
          case (trigger, auto) =>
            Seq("Auto dependency:", s"  Trigger: $trigger") ++ auto.map(dep => s"  Adds: $dep")
        }
        .mkString("\n")
    )


    val interpreterEc = singleThreadedExecutionContext("scala-interpreter")
    val updateBackgroundVariablesEc = singleThreadedExecutionContext("update-background-variables")

    val zeromqThreads = ZeromqThreads.create("scala-kernel")
    val kernelThreads = KernelThreads.create("scala-kernel")

    val initialClassLoader =
      if (options.specificLoader)
        JupyterApi.getClass.getClassLoader
      else
        Thread.currentThread().getContextClassLoader

    log.info("Creating interpreter")

    val interpreter = new ScalaInterpreter(
      updateBackgroundVariablesEcOpt = Some(updateBackgroundVariablesEc),
      extraRepos = options.extraRepository,
      extraBannerOpt = options.banner,
      extraLinks = extraLinks,
      predefCode = options.predefCode,
      predefFiles = predefFiles,
      automaticDependencies = autoDependencies,
      forceMavenProperties = forceProperties,
      mavenProfiles = mavenProfiles,
      initialClassLoader = initialClassLoader,
      logCtx = logCtx,
      metabrowse = options.metabrowse,
      lazyInit = true,
      trapOutput = options.trapOutput,
      disableCache = options.disableCache
    )
    log.info("Created interpreter")


    // Actually init Ammonite interpreter in background

    val initThread = new Thread("interpreter-init") {
      setDaemon(true)
      override def run() =
        try {
          log.info("Initializing interpreter (background)")
          interpreter.ammInterp
          log.info("Initialized interpreter (background)")
        } catch {
          case t: Throwable =>
            log.error(s"Caught exception while initializing interpreter, exiting", t)
            sys.exit(1)
        }
    }

    initThread.start()


    log.info("Running kernel")
    Kernel.create(interpreter, interpreterEc, kernelThreads, logCtx)
      .flatMap(_.runOnConnectionFile(connectionFile, "scala", zeromqThreads))
      .unsafeRunSync()
  }

}
