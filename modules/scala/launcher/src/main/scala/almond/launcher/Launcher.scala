package almond.launcher

import almond.channels.{Channel, Connection, ConnectionParameters, Message => RawMessage}
import almond.channels.relay.RelayServer
import almond.channels.zeromq.{ReusePort, ZeromqThreads}
import almond.cslogger.NotebookCacheLogger
import almond.directives.KernelOptions
import almond.interpreter.{ExecuteError, KernelSession}
import almond.interpreter.api.{DisplayData, OutputHandler}
import almond.interpreter.messagehandlers.MessageHandler
import almond.kernel.install.Install
import almond.kernel.{Kernel, KernelThreads, MessageFile}
import almond.launcher.directives.LauncherParameters
import almond.logger.{Level, Logger, LoggerContext}
import almond.protocol.{Execute, RawJson, Registration, Connection => JsonConnection}
import almond.util.ThreadUtil.singleThreadedExecutionContextExecutorService
import caseapp.core.RemainingArgs
import caseapp.core.app.CaseApp
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.github.plokhotnyuk.jsoniter_scala.core._
import coursier.exec.{ErrnoException, Execve}
import coursier.launcher.{BootstrapGenerator, ClassLoaderContent, ClassPathEntry, Parameters}
import dependency.ScalaParameters

import java.io.{File, FileOutputStream, PrintStream}
import java.lang.ref.Reference
import java.net.{InetSocketAddress, UnknownHostException}
import java.nio.channels.{ClosedSelectorException, SocketChannel}
import java.nio.file.{Files, Paths}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import scala.concurrent.duration.Duration

object Launcher extends CaseApp[LauncherOptions] {

  /** Command starting the actual kernel, short of the arguments telling it how to talk to the
    * frontend
    *
    * @param javaArgs
    *   JVM options, class path, and main class
    * @param tempFiles
    *   temporary files the kernel needs, to be deleted once it exits
    */
  private final case class KernelCommand(
    javaCommand: Seq[String],
    javaArgs: Seq[String],
    kernelArgs: Seq[String],
    tempFiles: Seq[os.Path]
  ) {
    def command(channelArgs: Seq[String]): Seq[String] =
      javaCommand ++ javaArgs ++ channelArgs ++ kernelArgs
  }

  private def actualKernelCommand(
    msgFileOpt: Option[os.Path],
    currentCellCount: Int,
    options: LauncherOptions,
    noExecuteInputFor: Seq[String],
    session: KernelSession,
    params0: LauncherParameters,
    kernelOptions: KernelOptions,
    outputHandler: OutputHandler,
    logCtx: LoggerContext
  ): (KernelCommand, String, Option[String]) = {

    val (scalaVersion, _) = LauncherInterpreter.computeScalaVersion(params0, options)
    // The kernel modules are published for binary Scala versions, and get the Scala version
    // the user asked for from the forced versions below.
    val scalaParams = ScalaParameters(scalaVersion)

    def content(entries: Seq[(coursierapi.Artifact, File)]): ClassLoaderContent = {
      val entries0 = entries.map {
        case (a, _) =>
          ClassPathEntry.Url(a.getUrl)
      }
      ClassLoaderContent(entries0)
    }

    val logger =
      if (options.quiet0)
        coursierapi.Logger.nop()
      else
        new NotebookCacheLogger(outputHandler, logCtx)

    val cache = coursierapi.Cache.create().withLogger(logger)
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
        s"scala-kernel-api_${scalaParams.scalaBinaryVersion}",
        Properties.version
      )
      fetch(dep, extraDeps = extraDeps) ++ fetch(dep, extraDeps = extraDeps, sources = true)
    }
    val kernelFiles = {
      val dep = coursierapi.Dependency.of(
        "sh.almond",
        s"scala-kernel_${scalaParams.scalaBinaryVersion}",
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

    // Not deleting these temporary files on exit ourselves: when exec-ing the actual kernel,
    // shutdown hooks are run before the exec, and would delete them before the kernel reads them
    val launcher = os.temp(prefix = "almond", suffix = ".jar", deleteOnExit = false)
    val params = Parameters.Bootstrap(
      Seq(content(apiFiles), content(kernelFiles)),
      Properties.kernelMainClass
    )
    BootstrapGenerator.generate(params, launcher.toNIO)

    val msgFileArgs = msgFileOpt.toSeq.flatMap { msgFile =>
      Seq[os.Shellable]("--leftover-messages", msgFile)
    }
    val noExecuteInputArgs = noExecuteInputFor.flatMap { id =>
      Seq("--no-execute-input-for", id, "--ignore-launcher-directives-in", id)
    }

    val optionsFileOpt =
      if (kernelOptions.isEmpty) None
      else {
        val asJson = KernelOptions.AsJson(kernelOptions)
        val bytes  = writeToArray(asJson)(KernelOptions.AsJson.codec)
        Some(os.temp(bytes, prefix = "almond-options-", suffix = ".json", deleteOnExit = false))
      }
    val optionsArgs = optionsFileOpt.toSeq.flatMap { optionsFile =>
      Seq[os.Shellable]("--kernel-options", optionsFile)
    }

    val jvmIdOpt = params0.jvm.filter(_.trim.nonEmpty)
    val javaCommand = jvmIdOpt match {
      case Some(jvmId) =>
        val jvmManager = coursierapi.JvmManager.create().setArchiveCache(
          coursierapi.ArchiveCache.create().withCache(cache)
        )
        val javaHome = os.Path(jvmManager.get(jvmId), os.pwd)
        val ext      = if (scala.util.Properties.isWin) ".exe" else ""
        Seq((javaHome / "bin" / s"java$ext").toString)
      case None =>
        params0.javaCmd.getOrElse(Seq("java"))
    }

    // JAVA_OPTS comes first, so that options from the command-line or from directives win
    val javaOptions = envOptions("JAVA_OPTS") ++ options.javaOpt ++ params0.javaOptions

    // Options on the command-line take precedence over the ones in JDK_JAVA_OPTIONS or
    // JAVA_TOOL_OPTIONS, so we don't pass our default max heap if users set one there
    val jvmEnvJavaOptions = Seq("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS").flatMap(envOptions)
    val memOptions =
      if ((javaOptions ++ jvmEnvJavaOptions).exists(setsMaxHeap)) Nil
      else Seq("-Xmx512m")

    val javaArgs = os.proc(
      memOptions,
      javaOptions,
      "-cp",
      (options.extraStartupClassPath :+ launcher.toString)
        .filter(_.nonEmpty)
        .mkString(File.pathSeparator),
      "coursier.bootstrap.launcher.Launcher"
    )
    val kernelArgs = os.proc(
      "--initial-cell-count",
      currentCellCount,
      msgFileArgs,
      noExecuteInputArgs,
      // so that the messages of the sub-kernel have the same session id as ours - both
      // processes are the same kernel from the point of view of the frontend
      "--kernel-session-id",
      session.id,
      optionsArgs,
      options.kernelOptions,
      params0.kernelOptions
    )

    val command = KernelCommand(
      javaCommand,
      javaArgs.commandChunks,
      kernelArgs.commandChunks,
      launcher +: optionsFileOpt.toSeq
    )
    (command, scalaVersion, jvmIdOpt)
  }

  private def envOptions(name: String): Seq[String] =
    Option(System.getenv(name)).toSeq.flatMap(_.trim.split("\\s+")).filter(_.nonEmpty)

  private def setsMaxHeap(javaOption: String): Boolean =
    javaOption.startsWith("-Xmx") ||
    javaOption.startsWith("-XX:MaxHeapSize=") ||
    javaOption.startsWith("-XX:MaxRAM=") ||
    javaOption.startsWith("-XX:MaxRAMPercentage=")

  // Loads the native library execve relies on, so that we can fall back to a sub-process
  // before closing anything if it can't be loaded
  private lazy val execveAvailable: Boolean =
    Execve.available() && {
      try {
        Class.forName("coursier.exec.Execve$LibC")
        true
      }
      catch {
        case _: Throwable =>
          false
      }
    }

  /** Whether we can bind the frontend sockets so that the actual kernel can take them over after an
    * exec (see [[ReusePort]])
    *
    * On macOS, wildcard addresses aren't supported: incoming connections go to the oldest socket
    * bound on wildcard addresses, which would be the placeholders rather than the kernel.
    */
  private def canReusePort(params: ConnectionParameters): Boolean =
    !scala.util.Properties.isWin &&
    params.transport == "tcp" &&
    ReusePort.supported &&
    execveAvailable && {
      try !(scala.util.Properties.isMac && ReusePort.resolve(params.ip).isAnyLocalAddress)
      catch {
        case _: UnknownHostException =>
          false
      }
    }

  private def resolveExecutable(command: String): Option[os.Path] = {
    def isExecutable(path: os.Path) = os.isFile(path) && Files.isExecutable(path.toNIO)
    if (command.contains(File.separatorChar))
      Some(os.Path(command, os.pwd)).filter(isExecutable)
    else
      Option(System.getenv("PATH"))
        .toSeq
        .flatMap(_.split(File.pathSeparator))
        .filter(_.nonEmpty)
        .iterator
        .map(dir => os.Path(dir, os.pwd) / command)
        .find(isExecutable)
  }

  // Major Java version, from the release file of its JVM if it has one, else by running it
  private def javaMajorVersion(java: os.Path): Option[Int] = {
    def parse(version: String): Option[Int] =
      version.stripPrefix("1.").takeWhile(_.isDigit) match {
        case ""  => None
        case num => Some(num.toInt)
      }
    val releaseFile = os.Path(java.toNIO.toRealPath()) / os.up / os.up / "release"
    val fromReleaseFile =
      if (os.isFile(releaseFile))
        os.read.lines(releaseFile).collectFirst {
          case line if line.startsWith("JAVA_VERSION=") =>
            line.stripPrefix("JAVA_VERSION=").stripPrefix("\"").stripSuffix("\"")
        }
      else
        None
    def fromSettings = {
      val res = os.proc(java, "-XshowSettings:properties", "-version")
        .call(stdin = os.Inherit, stdout = os.Pipe, stderr = os.Pipe, check = false)
      (res.err.lines() ++ res.out.lines()).map(_.trim).collectFirst {
        case line if line.startsWith("java.specification.version =") =>
          line.stripPrefix("java.specification.version =").trim
      }
    }
    fromReleaseFile.orElse(fromSettings).flatMap(parse)
  }

  /** The java executable to exec, if the actual kernel can be exec-ed
    *
    * The actual kernel needs Java >= 9 to bind ports with SO_REUSEPORT.
    */
  private def execJava(javaCommand: Seq[String]): Option[os.Path] =
    javaCommand match {
      case Seq(java) =>
        resolveExecutable(java).filter { path =>
          try javaMajorVersion(path).exists(_ >= 9)
          catch {
            case NonFatal(_) =>
              false
          }
        }
      case _ =>
        None
    }

  /** Binds placeholders on the ports of the frontend sockets (see [[ReusePort]])
    *
    * @return
    *   the placeholders, if all of them could be bound
    */
  private def bindPlaceholders(connectionFile: os.Path, log: Logger): Option[Seq[SocketChannel]] = {
    val params  = readFromArray(os.read.bytes(connectionFile))(JsonConnection.codec)
    val address = ReusePort.resolve(params.ip)
    val ports = Seq(
      params.shell_port,
      params.control_port,
      params.iopub_port,
      params.stdin_port,
      params.hb_port
    )
    val placeholders = new ListBuffer[SocketChannel]
    try {
      for (port <- ports)
        placeholders += ReusePort.placeholder(new InetSocketAddress(address, port))
      Some(placeholders.toList)
    }
    catch {
      case NonFatal(e) =>
        log.warn(
          "Cannot bind placeholder sockets, relaying messages to the actual kernel instead",
          e
        )
        placeholders.foreach(_.close())
        None
    }
  }

  /** Replaces the current process with the actual kernel
    *
    * `placeholders` keep the frontend ports bound during the exec, until the actual kernel binds
    * them again. They're inherited by the actual kernel, that keeps them open until it exits.
    */
  private def execActualKernel(
    java: os.Path,
    command: Seq[String],
    placeholders: Seq[SocketChannel],
    closeConnection: () => Unit
  ): Nothing = {
    val argv = java.toString +: command.tail
    System.err.println(s"Exec-ing ${argv.mkString(" ")}")
    closeConnection()
    val env = System.getenv().asScala.toVector.map { case (k, v) => s"$k=$v" }
    System.out.flush()
    System.err.flush()
    Reference.reachabilityFence(placeholders)
    try Execve.execve(java.toString, argv.toArray, env.toArray)
    catch {
      case e: ErrnoException =>
        e.printStackTrace(System.err)
    }
    // execve only returns if it failed, after running the shutdown hooks, and the ports were
    // released, so we can't fall back to starting the kernel in a sub-process
    System.err.println(s"Could not exec $java")
    System.err.flush()
    Runtime.getRuntime.halt(1)
    sys.error("Cannot happen")
  }

  private def launchActualKernel(
    proc: os.proc,
    relayServer: RelayServer,
    relayFile: os.Path,
    conn: Connection,
    logCtx: LoggerContext,
    ioRuntime: IORuntime
  ): Int = {

    System.err.println(s"Launching ${proc.command.flatMap(_.value).mkString(" ")}")
    val p = proc.spawn(stdin = os.Inherit, stdout = os.Inherit)
    val hook: Thread =
      new Thread("shutdown-kernel") {
        setDaemon(true)
        override def run(): Unit =
          if (p.isAlive()) {
            System.err.println("Shutting down underlying kernel")
            p.close()
            val timeout = 500.millis
            if (!p.waitFor(timeout.toMillis)) {
              System.err.println(
                s"Underlying kernel still running after $timeout, destroying it forcibly"
              )
              p.destroyForcibly()
            }
            System.err.println("Shut down underlying kernel")
          }
      }
    Runtime.getRuntime.addShutdownHook(hook)
    val socketOpt =
      try relayServer.accept(() => p.isAlive())
      finally os.remove(relayFile)
    socketOpt match {
      case Some(socket) =>
        RelayServer.forward(conn, socket, logCtx).unsafeRunSync()(ioRuntime)
      case None =>
        System.err.println("Sub-kernel exited before connecting to the launcher")
    }
    p.waitFor()
    try Runtime.getRuntime.removeShutdownHook(hook)
    catch {
      case e: IllegalStateException =>
        System.err.println("Ignoring error while trying to remove shutdown hook")
        e.printStackTrace(System.err)
    }
    val exitCode = p.exitCode()
    System.err.println(s"Sub-kernel exited with return code $exitCode")
    exitCode
  }

  def run(options: LauncherOptions, remainingArgs: RemainingArgs): Unit = {

    if (options.version) {
      println(Properties.version)
      sys.exit(0)
    }

    // checking it early, rather than having the kernel fail upon startup later on
    options.outputStyleOrExit()
    val maxMessageSize = options.maxMessageSizeOrExit()

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

    val interpreterEc = singleThreadedExecutionContextExecutorService("scala-launcher-interpreter")
    val cancellablesEc =
      singleThreadedExecutionContextExecutorService("scala-launcher-cancellables")

    val zeromqThreads0 =
      ZeromqThreads.create("scala-kernel-launcher").withMaxMessageSize(maxMessageSize)
    val kernelThreads = KernelThreads.create("scala-kernel-launcher")

    val registrationOpt = Registration.fromPathIfRegistration(Paths.get(connectionFile))
      .unsafeRunSync()(kernelThreads.ioRuntime)

    // Temporary files the actual kernel needs, to be deleted once it exits
    val tempFiles = new ListBuffer[os.Path]

    // If we're passed a registration file (kernel startup handshake), we bind random ports and send
    // them to the registration service ourselves (the actual kernel then gets messages through us,
    // see RelayServer)
    val kernelConnectionFile = registrationOpt match {
      case Some(registration) =>
        val content = writeToArray(JsonConnection.fromParams(registration.connectionParameters))
        val f = os.temp(
          content,
          prefix = "almond-kernel-connection-",
          suffix = ".json",
          perms = if (scala.util.Properties.isWin) null else "rw-------",
          deleteOnExit = false
        )
        tempFiles += f
        f.toString
      case None =>
        connectionFile
    }

    // If we can, we exec the actual kernel in our own process, and it binds the ports we bind
    // again, alongside the placeholders we keep bound in the meantime (see ReusePort). Else, we
    // keep the ports bound until the actual kernel exits, and relay messages to it (see
    // RelayServer). Either way, the ports stay reserved, and other processes can't bind them
    // while the frontend uses them.
    val reusePort = options.exec.getOrElse(true) && {
      val params = registrationOpt
        .map(_.connectionParameters)
        .getOrElse {
          JsonConnection.fromPath(Paths.get(connectionFile))
            .unsafeRunSync()(kernelThreads.ioRuntime)
            .connectionParameters
        }
      canReusePort(params)
    }
    log.debug(s"Binding ports with SO_REUSEPORT: $reusePort")
    val zeromqThreads = zeromqThreads0.withReusePort(reusePort)

    val interpreter = new LauncherInterpreter(
      kernelConnectionFile,
      options
    )

    val session = KernelSession.create(None, options.username.map(_.trim).filter(_.nonEmpty))

    val (run, conn) =
      Kernel
        .create(
          interpreter,
          interpreterEc,
          kernelThreads,
          cancellablesEc,
          logCtx,
          MessageHandler.empty,
          Set.empty,
          session
        )
        .flatMap(_.runOnConnectionFileAllowClose(
          Paths.get(kernelConnectionFile),
          "scala",
          zeromqThreads,
          Nil,
          autoClose = false,
          lingerDuration = Duration.Inf, // unused here
          bindToRandomPorts =
            if (registrationOpt.nonEmpty || options.bindToRandomPorts.getOrElse(true))
              Some(Paths.get(kernelConnectionFile))
            else None,
          registrationOpt = registrationOpt
        ))
        .unsafeRunSync()(kernelThreads.ioRuntime)
    val leftoverMessages: Seq[(Channel, RawMessage)] = run.unsafeRunSync()(kernelThreads.ioRuntime)

    val leftoverMessagesFileOpt =
      if (leftoverMessages.isEmpty) None
      else {
        val msgFile = MessageFile.from(leftoverMessages)
        val leftoverMessagesFile = os.temp(
          msgFile.asJson.value,
          prefix = "almond-launcher-leftover-messages-",
          suffix = ".json",
          deleteOnExit = false
        )
        tempFiles += leftoverMessagesFile
        Some(leftoverMessagesFile)
      }

    val firstMessageOpt = leftoverMessages
      .headOption
      .collect {
        case (Channel.Requests, m) =>
          almond.interpreter.Message.parse[RawJson](m).toOption // FIXME Log any error on the left?
      }
      .flatten

    val firstMessageIdOpt = firstMessageOpt.map(_.header.msg_id)

    val outputHandlerOpt = firstMessageOpt.map { firstMessage =>
      new LauncherOutputHandler(firstMessage, session, conn, kernelThreads.ioRuntime)
    }

    val maybeActualKernelCommand =
      try {
        val (launcherParams, kernelParams) =
          interpreter.params.processCustomDirectives(interpreter.kernelOptions)
        val (actualKernelCommand0, scalaVersion, jvmOpt) = actualKernelCommand(
          leftoverMessagesFileOpt,
          interpreter.lineCount,
          options,
          firstMessageIdOpt.toSeq,
          session,
          launcherParams,
          kernelParams,
          outputHandlerOpt.getOrElse(OutputHandler.NopOutputHandler),
          logCtx
        )

        if (!options.quiet0)
          for (outputHandler <- outputHandlerOpt) {
            val toPrint =
              s"Launching Scala $scalaVersion kernel" + jvmOpt.fold("")(jvm => s" with JVM $jvm")
            val toPrintHtml =
              s"Launching Scala <code>$scalaVersion</code> kernel" +
                jvmOpt.fold("")(jvm => s" with JVM <code>$jvm</code>")
            outputHandler.display(
              DisplayData()
                .add(DisplayData.ContentType.text, toPrint)
                .add(DisplayData.ContentType.html, toPrintHtml)
            )
          }

        Right(actualKernelCommand0)
      }
      catch {
        case NonFatal(e) if firstMessageOpt.nonEmpty =>
          val firstMessage = firstMessageOpt.getOrElse(sys.error("Cannot happen"))
          val err          = ExecuteError.error(fansi.Color.Red, fansi.Color.Green, Some(e), "")
          val errMsg = firstMessage.publish(
            session,
            Execute.errorType,
            Execute.Error("", "", List(err.message))
          )
          try
            conn.send(Channel.Publish, errMsg.asRawMessage).unsafeRunSync()(kernelThreads.ioRuntime)
          catch {
            case NonFatal(e) =>
              throw new Exception(e)
          }
          Left(e)
      }

    for (outputHandler <- outputHandlerOpt)
      outputHandler.done()

    def closeConnection(): Unit = {
      try
        conn.close(partial = false, lingerDuration = options.lingerDuration)
          .unsafeRunSync()(kernelThreads.ioRuntime)
      catch {
        case NonFatal(e) =>
          throw new Exception(e)
      }

      log.debug("Closing ZeroMQ context")
      IO(zeromqThreads.context.close())
        .evalOn(zeromqThreads.selectorOpenCloseEces)
        .unsafeRunSync()(kernelThreads.ioRuntime)
      log.debug("ZeroMQ context closed")
    }

    maybeActualKernelCommand match {
      case Right(kernelCommand) =>
        val tempFiles0 = tempFiles.toList ++ kernelCommand.tempFiles
        val execOpt =
          if (reusePort)
            execJava(kernelCommand.javaCommand).flatMap { java =>
              bindPlaceholders(os.Path(kernelConnectionFile, os.pwd), log).map((java, _))
            }
          else
            None
        execOpt match {
          case Some((java, placeholders)) =>
            val channelArgs = Seq("--connection-file", kernelConnectionFile, "--reuse-port") ++
              tempFiles0.flatMap(f => Seq("--delete-on-exit", f.toString))
            execActualKernel(
              java,
              kernelCommand.command(channelArgs) ++ remainingArgs.unparsed,
              placeholders,
              () => closeConnection()
            )
          case None =>
            for (f <- tempFiles0)
              f.toIO.deleteOnExit()
            val relayServer = RelayServer.create()
            val relayFile = os.temp(
              relayServer.relayFile.asJson,
              prefix = "almond-relay-",
              suffix = ".json",
              perms = if (scala.util.Properties.isWin) null else "rw-------"
            )
            val proc = os.proc(
              kernelCommand.command(Seq("--relay-file", relayFile.toString)),
              remainingArgs.unparsed
            )
            val exitCode =
              try
                launchActualKernel(
                  proc,
                  relayServer,
                  relayFile,
                  conn,
                  logCtx,
                  kernelThreads.ioRuntime
                )
              finally
                closeConnection()
            if (exitCode != 0)
              sys.exit(exitCode)
        }
      case Left(e) =>
        for (f <- tempFiles)
          f.toIO.deleteOnExit()
        closeConnection()
        throw new Exception(e)
    }
  }
}
