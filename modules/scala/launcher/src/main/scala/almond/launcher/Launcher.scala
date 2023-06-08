package almond.launcher

import almond.channels.{Channel, Connection, Message => RawMessage}
import almond.channels.zeromq.ZeromqThreads
import almond.kernel.install.Install
import almond.kernel.{Kernel, KernelThreads, MessageFile}
import almond.logger.{Level, LoggerContext}
import almond.protocol.RawJson
import almond.util.ThreadUtil.singleThreadedExecutionContext
import caseapp.core.RemainingArgs
import caseapp.core.app.CaseApp
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import coursier.launcher.{BootstrapGenerator, ClassLoaderContent, ClassPathEntry, Parameters}
import dependency.ScalaParameters

import java.io.{File, FileOutputStream, PrintStream}
import java.nio.channels.ClosedSelectorException

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

object Launcher extends CaseApp[LauncherOptions] {

  private def launchActualKernel(
    connectionFile: String,
    msgFileOpt: Option[os.Path],
    currentCellCount: Int,
    options: LauncherOptions,
    noExecuteInputFor: Seq[String],
    params0: LauncherParameters
  ): Unit = {

    val requestedScalaVersion = params0.scala
      .orElse(options.scala.map(_.trim).filter(_.nonEmpty))
      .getOrElse(Properties.defaultScalaVersion)

    val scalaVersion = requestedScalaVersion match {
      case "2.12"       => Properties.defaultScala212Version
      case "2" | "2.13" => Properties.defaultScala213Version
      case "3"          => Properties.defaultScalaVersion
      case _            => requestedScalaVersion
    }

    def content(entries: Seq[(coursierapi.Artifact, File)]): ClassLoaderContent = {
      val entries0 = entries.map {
        case (a, _) =>
          ClassPathEntry.Url(a.getUrl)
      }
      ClassLoaderContent(entries0)
    }

    val cache = coursierapi.Cache.create().withLogger(coursierapi.Logger.progressBars())
    val forcedVersions =
      if (scalaVersion.startsWith("2."))
        Map(
          coursierapi.Module.of("org.scala-lang", "scala-library")  -> scalaVersion,
          coursierapi.Module.of("org.scala-lang", "scala-compiler") -> scalaVersion,
          coursierapi.Module.of("org.scala-lang", "scala-reflect")  -> scalaVersion
        )
      else
        Map(
          coursierapi.Module.of("org.scala-lang", "scala3-library_3")  -> scalaVersion,
          coursierapi.Module.of("org.scala-lang", "scala3-compiler_3") -> scalaVersion,
          coursierapi.Module.of("org.scala-lang", "scala3-interfaces") -> scalaVersion
        )
    val resolutionParams = coursierapi.ResolutionParams.create()
      .forceVersions(forcedVersions.asJava)
    def fetcher = coursierapi.Fetch.create()
      .withCache(cache)
      .addRepositories(coursierapi.MavenRepository.of("https://jitpack.io"))
      .withResolutionParams(resolutionParams)
    def fetch(
      dep: coursierapi.Dependency,
      extraDeps: Seq[coursierapi.Dependency] = Nil,
      sources: Boolean = false
    ) = {
      var fetcher0 = fetcher
      if (sources)
        fetcher0 = fetcher0.addClassifiers("sources")
      fetcher0 = fetcher0.addDependencies(dep)
      for (dep0 <- extraDeps)
        fetcher0 = fetcher0.addDependencies(dep0)
      fetcher0
        .fetchResult()
        .getArtifacts
        .asScala
        .toVector
        .map(e => (e.getKey, e.getValue))
    }
    val apiFiles = {
      val scalaParams = ScalaParameters(scalaVersion)
      val extraDeps = options.sharedDependencies.map { str =>
        val dep = dependency.parser.DependencyParser.parse(str) match {
          case Left(err) =>
            sys.error(s"Malformed shared dependency '$str': $err")
          case Right(dep0) => dep0
        }

        val javaDep = dep.applyParams(scalaParams)
        val version = if (javaDep.version == "_") Properties.version else javaDep.version
        coursierapi.Dependency.of(javaDep.organization, javaDep.name, version)
      }
      val dep = coursierapi.Dependency.of(
        "sh.almond",
        s"scala-kernel-api_$scalaVersion",
        Properties.version
      )
      fetch(dep, extraDeps = extraDeps) ++ fetch(dep, extraDeps = extraDeps, sources = true)
    }
    val kernelFiles = {
      val dep = coursierapi.Dependency.of(
        "sh.almond",
        s"scala-kernel_$scalaVersion",
        Properties.version
      )
      val files = fetch(dep) ++ fetch(dep, sources = true)
      files.filter {
        val set = apiFiles.map(_._2).toSet
        t =>
          val f = t._2
          !set.contains(f)
      }
    }

    val launcher = os.temp(prefix = "almond", suffix = ".jar")
    val params = Parameters.Bootstrap(
      Seq(content(apiFiles), content(kernelFiles)),
      Properties.kernelMainClass
    )
    BootstrapGenerator.generate(params, launcher.toNIO)

    val msgFileArgs = msgFileOpt.toSeq.flatMap { msgFile =>
      Seq[os.Shellable]("--leftover-messages", msgFile)
    }
    val noExecuteInputArgs = noExecuteInputFor.flatMap { id =>
      Seq("--no-execute-input-for", id)
    }

    val javaCommand = params0.jvm.filter(_.trim.nonEmpty) match {
      case Some(jvmId) =>
        val jvmManager = coursierapi.JvmManager.create().setArchiveCache(
          coursierapi.ArchiveCache.create().withCache(cache)
        )
        val javaHome = os.Path(jvmManager.get(jvmId), os.pwd)
        val ext      = if (scala.util.Properties.isWin) ".exe" else ""
        (javaHome / "bin" / s"java$ext").toString
      case None =>
        "java"
    }

    val javaOptions = options.javaOpt ++ params0.javaOptions

    val memOptions =
      if (javaOptions.exists(_.startsWith("-Xmx"))) Nil
      else Seq("-Xmx512m")

    val proc = os.proc(
      javaCommand,
      memOptions,
      javaOptions,
      "-cp",
      (options.extraStartupClassPath :+ launcher.toString)
        .filter(_.nonEmpty)
        .mkString(File.pathSeparator),
      "coursier.bootstrap.launcher.Launcher",
      "--connection-file",
      connectionFile,
      "--initial-cell-count",
      currentCellCount,
      msgFileArgs,
      noExecuteInputArgs,
      options.kernelOptions
    )
    System.err.println(s"Launching ${proc.command.flatMap(_.value).mkString(" ")}")
    val p = proc.spawn(stdin = os.Inherit, stdout = os.Inherit)
    val hook: Thread =
      new Thread("shutdown-kernel") {
        setDaemon(true)
        override def run(): Unit =
          if (p.isAlive()) {
            p.close()
            val timeout = 500.millis
            if (!p.waitFor(timeout.toMillis)) {
              System.err.println(
                s"Underlying kernel still running after $timeout, destroying it forcibly"
              )
              p.destroyForcibly()
            }
          }
      }
    Runtime.getRuntime.addShutdownHook(hook)
    p.waitFor()
    try Runtime.getRuntime.removeShutdownHook(hook)
    catch {
      case e: IllegalStateException =>
        System.err.println("Ignoring error while trying to remove shutdown hook")
        e.printStackTrace(System.err)
    }
    val exitCode = p.exitCode()
    System.err.println(s"Sub-kernel exited with return code $exitCode")
    if (exitCode != 0)
      sys.exit(exitCode)
  }

  def run(options: LauncherOptions, remainingArgs: RemainingArgs): Unit = {

    // FIXME We'd need coursier-interface to allow us to do these:

    // if (Properties.isWin && isGraalvmNativeImage)
    //   // have to be initialized before running (new Argv0).get because Argv0SubstWindows uses csjniutils library
    //   // The DLL loaded by LoadWindowsLibrary is statically linke/d in
    //   // the Scala CLI native image, no need to manually load it.
    //   coursier.jniutils.LoadWindowsLibrary.assumeInitialized()

    // coursier.Resolve.proxySetup()

    // if (Properties.isWin && System.console() != null && coursier.paths.Util.useJni())
    //   // Enable ANSI output in Windows terminal
    //   coursier.jniutils.WindowsAnsiTerminal.enableAnsiOutput()

    val logCtx = Level.fromString(options.log.getOrElse("warn")) match {
      case Left(err) =>
        Console.err.println(err)
        sys.exit(1)
      case Right(level) =>
        options.logTo match {
          case None =>
            LoggerContext.stderr(
              level,
              options.color.getOrElse(true),
              addPid = true
            )
          case Some(f) =>
            LoggerContext.printStream(
              level,
              new PrintStream(new FileOutputStream(new File(f))),
              options.color.getOrElse(true),
              addPid = true
            )
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
        extraStartupClassPath = Nil
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

    val colors =
      if (options.color.getOrElse(true)) LauncherInterpreter.Colors.default
      else LauncherInterpreter.Colors.blackWhite

    val interpreterEc = singleThreadedExecutionContext("scala-launcher-interpreter")

    val zeromqThreads = ZeromqThreads.create("scala-kernel-launcher")
    val kernelThreads = KernelThreads.create("scala-kernel-launcher")

    var connOpt       = Option.empty[Connection]
    var closeExpected = false
    val interpreter = new LauncherInterpreter(
      connectionFile,
      options,
      () => {
        val conn = connOpt.getOrElse(sys.error("No connection"))
        closeExpected = true
        conn.close.unsafeRunSync()(IORuntime.global)
      }
    )

    val (run, conn) = Kernel.create(interpreter, interpreterEc, kernelThreads, logCtx)
      .flatMap(_.runOnConnectionFileAllowClose(connectionFile, "scala", zeromqThreads, Nil))
      .unsafeRunSync()(IORuntime.global)
    connOpt = Some(conn)
    val leftoverMessages: Seq[(Channel, RawMessage)] = run.unsafeRunSync()(IORuntime.global)

    log.debug("Closing ZeroMQ context")
    IO(zeromqThreads.context.close())
      .evalOn(zeromqThreads.pollingEc)
      .unsafeRunSync()(IORuntime.global)
    log.debug("ZeroMQ context closed")

    val leftoverMessagesFileOpt =
      if (leftoverMessages.isEmpty) None
      else {
        val msgFile = MessageFile.from(leftoverMessages)
        val leftoverMessagesFile = os.temp(
          msgFile.asJson.value,
          prefix = "almond-launcher-leftover-messages-",
          suffix = ".json"
        )
        Some(leftoverMessagesFile)
      }

    val firstMessageIdOpt = leftoverMessages
      .headOption
      .collect {
        case (Channel.Requests, m) =>
          almond.interpreter.Message.parse[RawJson](m).toOption // FIXME Log any error on the left?
      }
      .flatten
      .map(_.header.msg_id)

    launchActualKernel(
      connectionFile,
      leftoverMessagesFileOpt,
      interpreter.lineCount,
      options,
      firstMessageIdOpt.toSeq,
      interpreter.params
    )
  }
}
