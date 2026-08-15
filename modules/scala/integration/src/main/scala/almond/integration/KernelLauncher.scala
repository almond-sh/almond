package almond.integration

import almond.channels.zeromq.ZeromqThreads
import almond.channels.{Channel, Connection, ConnectionParameters, Message => RawMessage}
import almond.protocol.{Connection => ConnectionSpec, KernelSpec}
import almond.testkit.Dsl._
import almond.testkit.{ClientStreams, TestLogging}
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import fs2.concurrent.SignallingRef
import io.github.alexarchambault.testutil.{TestOutput, TestUtil}
import org.zeromq.ZMQ

import java.io.{File, IOException}
import java.nio.channels.ClosedSelectorException
import java.nio.file.FileSystemException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.util.control.NonFatal
import scala.util.Properties

object KernelLauncher {

  def enableOutputFrame  = System.getenv("CI") == null
  def enableSilentOutput = true
  def printOutputOnError = true

  lazy val testScala212Version = sys.props.getOrElse(
    "almond.test.scala212-version",
    sys.error("almond.test.scala212-version Java property not set")
  )

  lazy val testScala213Version = sys.props.getOrElse(
    "almond.test.scala213-version",
    sys.error("almond.test.scala213-version Java property not set")
  )

  lazy val kernelShapelessVersion = sys.props.getOrElse(
    "almond.test.shapeless-version-pulled-by-kernel",
    sys.error("almond.test.shapeless-version-pulled-by-kernel Java property not set")
  )

  lazy val localRepoRoot = sys.props.get("almond.test.local-repo")
    .map(os.Path(_))
    .getOrElse {
      sys.error("almond.test.local-repo Java property not set")
    }

  lazy val almondVersion = sys.props.getOrElse(
    "almond.test.version",
    sys.error("almond.test.version Java property not set")
  )

  lazy val cs = sys.props.getOrElse(
    "almond.test.cs-launcher",
    sys.error("almond.test.cs-launcher Java property not set")
  )

  object TmpDir {

    private lazy val baseTmpDir = {
      Option(System.getenv("ALMOND_INTEGRATION_TMP")).getOrElse {
        sys.error("ALMOND_INTEGRATION_TMP not set")
      }
      val base = os.Path(System.getenv("ALMOND_INTEGRATION_TMP"))
      val rng  = new SecureRandom
      val d    = base / s"run-${math.abs(rng.nextInt().toLong)}"
      os.makeDir.all(d)
      Runtime.getRuntime.addShutdownHook(
        new Thread("scala-cli-its-clean-up-tmp-dir") {
          setDaemon(true)
          override def run(): Unit =
            try os.remove.all(d)
            catch {
              case NonFatal(_) =>
                System.err.println(s"Could not remove $d, ignoring it.")
            }
        }
      )
      d
    }

    private val tmpCount = new AtomicInteger

    def withTmpDir[T](f: os.Path => T): T = {
      val tmpDir = baseTmpDir / s"test-${tmpCount.incrementAndGet()}"
      os.makeDir.all(tmpDir)
      val tmpDir0 = os.Path(tmpDir.toIO.getCanonicalFile)
      def removeAll(): Unit =
        try os.remove.all(tmpDir0)
        catch {
          case ex: IOException =>
            System.err.println(s"Ignoring $ex while removing $tmpDir0")
        }
      try f(tmpDir0)
      finally removeAll()
    }

    def tmpDir(): os.Path = {
      val tmpDir = baseTmpDir / s"test-${tmpCount.incrementAndGet()}"
      os.makeDir.all(tmpDir)
      os.Path(tmpDir.toIO.getCanonicalFile)
    }
  }

  @tailrec
  private def retryPeriodicallyUntil[T](retryUntil: Long, period: Long)(f: => Option[T]): T = {
    val now = System.currentTimeMillis()
    if (now > retryUntil) throw new TimeoutException
    else
      f match {
        case Some(t) => t
        case None =>
          Thread.sleep(math.min(period, retryUntil - now))
          retryPeriodicallyUntil(retryUntil, period)(f)
      }
  }
}

class KernelLauncher(
  val defaultScalaVersion: String,
  closeForcibly: Boolean = true
) {

  private lazy val ioRuntime = IORuntime.global

  /** How long we should wait for messages when closing zeromq connections? */
  def lingerDuration: Duration = 30.seconds

  /** How long after session start should we time out if a session takes too long to run */
  def sessionTimeout: Duration = 3.minutes

  import KernelLauncher._

  def kernelBindToRandomPorts: Boolean = true

  private def generateLauncher(output: TestOutput, extraOptions: Seq[String] = Nil): os.Path = {
    val perms: os.PermSet = if (Properties.isWin) null else "rwx------"
    val tmpDir            = os.temp.dir(prefix = "almond-tests", perms = perms)
    val (jarDest, extraOpts) =
      if (Properties.isWin)
        (tmpDir / "launcher", Seq("--bat"))
      else {
        val launcher = tmpDir / "launcher.jar"
        (launcher, Nil)
      }
    val repoArgs = Seq(
      "--no-default",
      "-r",
      s"ivy:${localRepoRoot.toNIO.toUri.toASCIIString.stripSuffix("/")}/[defaultPattern]",
      "-r",
      "ivy2Local",
      "-r",
      "central",
      "-r",
      "jitpack"
    )
    val launcherArgs = Seq(
      s"sh.almond:::scala-kernel:$almondVersion",
      "--shared",
      "sh.almond:::scala-kernel-api",
      "--scala",
      defaultScalaVersion
    )
    val res = os.proc(
      cs,
      "bootstrap",
      "--embed-files=false",
      "--default=true",
      "--sources",
      extraOpts,
      repoArgs,
      "-o",
      jarDest,
      launcherArgs,
      extraOptions
    )
      .call(
        stdin = os.Inherit,
        stdout = output.processOutput,
        stderr = output.processOutput,
        check = false
      )
    if (res.exitCode != 0)
      sys.error(
        s"""Error generating an Almond $almondVersion launcher for Scala $defaultScalaVersion
           |
           |If that error is unexpected, you might want to:
           |- remove out/repo
           |    rm -rf out/repo
           |- remove cached version computation in the build:
           |    find out -name "*publishVersion*" -print0 | xargs -0 rm -f
           |
           |Then try again.
           |""".stripMargin
      )
    jarDest
  }

  private var jarLauncher0: os.Path = null
  private def jarLauncher(output: TestOutput) = {
    if (jarLauncher0 == null)
      synchronized {
        if (jarLauncher0 == null)
          jarLauncher0 = generateLauncher(output)
      }

    jarLauncher0
  }

  private lazy val threads = ZeromqThreads.create("almond-tests")

  // not sure why, closing the context right after running a test on Windows
  // creates a deadlock (on the CI, at least)
  private def perTestZeroMqContext = !Properties.isWin

  private def stackTracePrinterThread(output: TestOutput): Thread =
    new Thread("stack-trace-printer") {
      import scala.jdk.CollectionConverters._
      setDaemon(true)
      override def run(): Unit =
        try {
          output.printStream.println("stack-trace-printer thread starting")
          while (true) {
            Thread.sleep(1.minute.toMillis)
            Thread.getAllStackTraces
              .asScala
              .toMap
              .toVector
              .sortBy(_._1.getId)
              .foreach {
                case (t, stack) =>
                  output.printStream.println(s"Thread $t (${t.getState}, ${t.getId})")
                  for (e <- stack)
                    output.printStream.println(s"  $e")
                  output.printStream.println()
              }
          }
        }
        catch {
          case _: InterruptedException =>
            output.printStream.println("stack-trace-printer thread interrupted")
        }
        finally
          output.printStream.println("stack-trace-printer thread exiting")
    }

  def session(
    conn: Connection,
    ctx: ZMQ.Context,
    output: TestOutput,
    ioRuntime: IORuntime
  ): Session with AutoCloseable =
    new Session with AutoCloseable {
      def helperIORuntime = ioRuntime
      def run(streams: ClientStreams): Unit = {

        val poisonPill: (Channel, RawMessage) = null

        val s = SignallingRef[IO, Boolean](false).unsafeRunSync()(ioRuntime)

        val t = for {
          fib1 <- conn.sink(streams.source).compile.drain.start
          fib2 <- streams.sink(conn.stream().interruptWhen(s)).compile.drain.start
          _ <- fib1.join.attempt.flatMap {
            case Left(e)  => IO.raiseError(new Exception(e))
            case Right(r) => IO.pure(r)
          }
          _ <- s.set(true)
          _ <- fib2.join.attempt.flatMap {
            case Left(e: ClosedSelectorException) => IO.pure(())
            case Left(e)                          => IO.raiseError(new Exception(e))
            case Right(r)                         => IO.pure(r)
          }
        } yield ()

        try Await.result(t.unsafeToFuture()(ioRuntime), 1.minute)
        catch {
          case NonFatal(e) => throw new Exception(e)
        }
      }

      def close(): Unit = {
        conn.close(partial = false, lingerDuration = lingerDuration)
          .unsafeRunTimed(2.minutes)(ioRuntime)
          .getOrElse {
            sys.error("Timeout when closing ZeroMQ connections")
          }

        if (perTestZeroMqContext) {
          val t = stackTracePrinterThread(output)
          try {
            t.start()
            output.printStream.println("Closing test ZeroMQ context")
            IO(ctx.close())
              .evalOn(threads.pollingEces)
              .unsafeRunTimed(2.minutes)(ioRuntime)
              .getOrElse {
                sys.error("Timeout when closing ZeroMQ context")
              }
            output.printStream.println("Test ZeroMQ context closed")
          }
          finally
            t.interrupt()
        }
      }
    }

  private def runner(output0: TestOutput): Runner with AutoCloseable =
    new Runner with AutoCloseable {

      def output: Option[TestOutput] = Some(output0)

      var proc: os.SubProcess = null
      var sessions            = List.empty[Session with AutoCloseable]
      var jupyterDirs         = List.empty[os.Path]

      private def setupJupyterDir[T](
        options: Seq[String],
        launcherOptions: Seq[String],
        extraClassPath: Seq[String],
        output: TestOutput
      ): (os.Path => os.Shellable, Map[String, String]) = {

        val kernelId = "almond-it"

        val baseCmd: os.Shellable = {
          val jarLauncher0 =
            if (launcherOptions.isEmpty)
              jarLauncher(output)
            else
              generateLauncher(output, launcherOptions)
          val baseCp = (extraClassPath :+ jarLauncher0.toString)
            .filter(_.nonEmpty)
            .mkString(File.pathSeparator)
          Seq[os.Shellable](
            "java",
            "-Xmx1g",
            "-cp",
            baseCp,
            "coursier.bootstrap.launcher.Launcher"
          )
        }

        val extraStartupClassPathOpts =
          extraClassPath.flatMap(elem => Seq("--extra-startup-class-path", elem))

        val dir = TmpDir.tmpDir()
        jupyterDirs = dir :: jupyterDirs
        val proc0 = os.proc(
          baseCmd,
          "--log",
          "debug",
          "--color=false",
          "--install",
          "--jupyter-path",
          dir,
          "--id",
          kernelId,
          extraStartupClassPathOpts,
          options
        )
        proc0.call(stdin = os.Inherit, stdout = output.processOutput, stderr = output.processOutput)

        val specFile = dir / kernelId / "kernel.json"
        val spec     = readFromArray(os.read.bytes(specFile))(KernelSpec.codec)

        val f: os.Path => os.Shellable =
          connFile =>
            spec.argv.map {
              case "{connection_file}" => connFile: os.Shellable
              case arg                 => arg: os.Shellable
            }
        (f, spec.env)
      }

      def withSession[T](options: String*)(f: Session => T)(implicit sessionId: SessionId): T =
        withRunnerSession(options, Nil, Nil)(f)
      def withExtraClassPathSession[T](extraClassPath: String*)(options: String*)(f: Session => T)(
        implicit sessionId: SessionId
      ): T =
        withRunnerSession(options, Nil, extraClassPath)(f)
      def withLauncherOptionsSession[T](launcherOptions: String*)(options: String*)(
        f: Session => T
      )(implicit sessionId: SessionId): T =
        withRunnerSession(options, launcherOptions, Nil)(f)

      def withRunnerSession[T](
        options: Seq[String],
        launcherOptions: Seq[String],
        extraClassPath: Seq[String]
      )(f: Session => T)(implicit sessionId: SessionId): T =
        TestUtil.runWithTimeout(Some(sessionTimeout).collect { case f: FiniteDuration => f }) {
          implicit val sess = runnerSession(options, launcherOptions, extraClassPath, output0)
          var running       = true

          val currentThread = Thread.currentThread()

          val t: Thread =
            new Thread("watch-kernel-proc") {
              setDaemon(true)
              override def run(): Unit = {
                var done = false
                while (running && !done)
                  done = proc.waitFor(100L)
                if (running && done) {
                  val retCode = proc.exitCode()
                  output0.printStream.println(
                    s"Kernel process exited with code $retCode, interrupting test"
                  )
                  currentThread.interrupt()
                }
              }
            }

          t.start()
          try {
            val t0 = f(sess)
            if (Properties.isWin)
              // On Windows, exit the kernel manually from the inside, so that all involved processes
              // exit cleanly. A call to Process#destroy would only destroy the first kernel process,
              // not any of its sub-processes (which would stay around, and such processes would end up
              // filling up memory on Windows).
              exit()
            t0
          }
          finally {
            running = false
            close(Some(output0))
          }
        }

      private def runnerSession(
        options: Seq[String],
        launcherOptions: Seq[String],
        extraClassPath: Seq[String],
        output: TestOutput
      ): Session = {

        close(Some(output))

        val dir      = TmpDir.tmpDir()
        val connFile = dir / "connection.json"

        val params =
          if (kernelBindToRandomPorts) ConnectionParameters.randomZeroPorts()
          else ConnectionParameters.randomLocal()
        val connDetails = ConnectionSpec.fromParams(params)

        os.write(connFile, writeToArray(connDetails))
        val initialConnFileLastModified = os.mtime(connFile)
        if (kernelBindToRandomPorts)
          // just in case, to be sure the the mtime is newer when the kernel
          // updates the connection file
          Thread.sleep(2L)

        val (command, specExtraEnv) = {
          val (f, env0) = setupJupyterDir(options, launcherOptions, extraClassPath, output)
          (f(connFile), env0)
        }

        output.printStream.println(s"Running ${command.value.mkString(" ")}")
        val extraEnv = {
          val baseRepos = sys.env.getOrElse(
            "COURSIER_REPOSITORIES",
            "ivy2Local|central"
          )
          Map(
            "COURSIER_REPOSITORIES" ->
              s"$baseRepos|ivy:${localRepoRoot.toNIO.toUri.toASCIIString.stripSuffix("/")}/[defaultPattern]"
          )
        }

        assert(proc == null)
        proc = os.proc(command).spawn(
          cwd = dir,
          env = extraEnv ++ specExtraEnv,
          stdin = os.Inherit,
          stdout = output.processOutput,
          stderr = output.processOutput
        )

        val ctx =
          if (perTestZeroMqContext) ZMQ.context(4)
          else threads.context

        val conn = {
          val updatedParams =
            if (kernelBindToRandomPorts) {
              retryPeriodicallyUntil(
                System.currentTimeMillis() + 2.minutes.toMillis,
                500.millis.toMillis
              ) {
                val connFileLastModified = os.mtime(connFile)
                if (connFileLastModified > initialConnFileLastModified) Some(())
                else None
              }
              val conn = readFromArray(os.read.bytes(connFile))(ConnectionSpec.codec)
              conn.connectionParameters
            }
            else {
              if (System.getenv("CI") != null) {
                val delay = 4.seconds
                output.printStream.println(s"Waiting $delay for the kernel to start")
                Thread.sleep(delay.toMillis)
                output.printStream.println("Done waiting")
              }
              params
            }

          val delay =
            if (kernelBindToRandomPorts)
              // we already waited for the connection file to be updated, the kernel should already be
              // listening for connections at that time
              20.seconds
            else
              2.minutes
          val conn0 = updatedParams
            .channels(
              bind = false,
              threads.copy(context = ctx),
              lingerPeriod = Some(Duration.Inf),
              logCtx = TestLogging.logCtxForOutput(output),
              identityOpt = Some(UUID.randomUUID().toString),
              bindToRandomPorts = false
            )
            .unsafeRunTimed(delay)(ioRuntime)
            .getOrElse {
              sys.error("Timeout when creating ZeroMQ connections")
            }

          conn0.open.unsafeRunTimed(delay)(ioRuntime).getOrElse {
            sys.error("Timeout when opening ZeroMQ connections")
          }

          conn0
        }

        val sess = session(conn, ctx, output, ioRuntime)
        sessions = sess :: sessions
        sess
      }

      def close(): Unit =
        close(None)
      def close(output: Option[TestOutput]): Unit = {
        val ps = output match {
          case Some(output0) => output0.printStream
          case None          => System.err
        }
        sessions.foreach(_.close())
        sessions = Nil
        jupyterDirs.foreach { dir =>
          try os.remove.all(dir)
          catch {
            case e: FileSystemException if Properties.isWin =>
              ps.println(s"Ignoring $e while trying to remove $dir")
          }
        }
        jupyterDirs = Nil
        if (proc != null) {
          if (closeForcibly) {
            proc.close()
            if (proc.isAlive()) {
              if (!proc.waitFor(3.seconds.toMillis)) {
                ps.println(
                  "Test kernel still running, destroying it forcibly"
                )
                proc.destroyForcibly()
              }
            }
            else
              ps.println("Kernel already exited")
          }
          else if (proc.isAlive()) {
            ps.println("Waiting for kernel to exit")
            proc.waitFor(Long.MaxValue)
          }
          else
            ps.println("Kernel already exited")
          proc = null
        }
      }
    }

  def withKernel[T](f: Runner => T): T = {

    var runner0: Runner with AutoCloseable = null

    val output = new TestOutput(
      enableOutputFrame = enableOutputFrame,
      enableSilentOutput = enableSilentOutput
    )

    var success = false
    try {
      output.start()
      runner0 = runner(output)
      val res = f(runner0)
      success = true
      res
    }
    finally {
      if (runner0 != null)
        runner0.close()
      output.close(success = success, printOutputOnError = printOutputOnError)
    }
  }

}
