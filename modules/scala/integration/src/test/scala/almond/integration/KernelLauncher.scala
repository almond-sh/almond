package almond.integration

import almond.channels.zeromq.ZeromqThreads
import almond.channels.{Channel, Connection, ConnectionParameters, Message => RawMessage}
import almond.testkit.Dsl._
import almond.testkit.{ClientStreams, TestLogging}
import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import fs2.concurrent.SignallingRef

import java.io.{File, IOException}
import java.nio.channels.ClosedSelectorException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal
import scala.util.Properties

object KernelLauncher {

  object TmpDir {

    private lazy val baseTmpDir = {
      Option(System.getenv("ALMOND_INTEGRATION_TMP")).getOrElse {
        sys.error("ALMOND_INTEGRATION_TMP not set")
      }
      val base = os.Path(System.getenv("ALMOND_INTEGRATION_TMP"), os.pwd)
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

  lazy val localRepoRoot = sys.props.get("almond.test.local-repo")
    .map(os.Path(_, os.pwd))
    .getOrElse {
      sys.error("almond.test.local-repo Java property not set")
    }

  lazy val launcherVersion = sys.props.getOrElse(
    "almond.test.launcher-version",
    sys.error("almond.test.launcher-version Java property not set")
  )

  lazy val launcherScalaVersion = sys.props.getOrElse(
    "almond.test.launcher-scala-version",
    sys.error("almond.test.launcher-scala-version Java property not set")
  )

  lazy val cs = sys.props.getOrElse(
    "almond.test.cs-launcher",
    sys.error("almond.test.cs-launcher Java property not set")
  )

  def generateLauncher(extraOptions: Seq[String] = Nil): (os.Path, os.Path) = {
    val perms: os.PermSet = if (Properties.isWin) null else "rwx------"
    val tmpDir            = os.temp.dir(prefix = "almond-tests", perms = perms)
    val (jarDest, runnerDest, extraOpts) =
      if (Properties.isWin)
        (tmpDir / "launcher", tmpDir / "launcher.bat", Seq("--bat"))
      else {
        val launcher = tmpDir / "launcher.jar"
        (launcher, launcher, Nil)
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
    os.proc(
      cs,
      "bootstrap",
      "--hybrid",
      extraOpts,
      repoArgs,
      "-o",
      jarDest,
      s"sh.almond:::scala-kernel:$launcherVersion",
      "--shared",
      "sh.almond:::scala-kernel-api",
      "--scala",
      launcherScalaVersion,
      extraOptions
    )
      .call(stdin = os.Inherit, stdout = os.Inherit)
    (jarDest, runnerDest)
  }

  lazy val (jarLauncher, launcher) = generateLauncher()

  lazy val threads = ZeromqThreads.create("almond-tests")

  def session(conn: Connection): Session with AutoCloseable =
    new Session with AutoCloseable {
      def run(streams: ClientStreams): Unit = {

        val poisonPill: (Channel, RawMessage) = null

        val s = {
          implicit val shift = IO.contextShift(ExecutionContext.global)
          SignallingRef[IO, Boolean](false).unsafeRunSync()
        }

        implicit val contextShift =
          IO.contextShift(ExecutionContext.global) // hope that EC will do the job…
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

        try Await.result(t.unsafeToFuture(), 1.minute)
        catch {
          case NonFatal(e) => throw new Exception(e)
        }
      }

      def close(): Unit =
        conn.close.unsafeRunSync()
    }

  def runner(): Runner with AutoCloseable =
    new Runner with AutoCloseable {

      var proc: os.SubProcess = null
      var sessions            = List.empty[Session with AutoCloseable]

      def apply(options: String*): Session =
        runnerSession(options, Nil, Nil)
      def withExtraJars(extraJars: os.Path*)(options: String*): Session =
        runnerSession(options, Nil, extraJars)
      def withLauncherOptions(launcherOptions: String*)(options: String*): Session =
        runnerSession(options, launcherOptions, Nil)

      private def runnerSession(
        options: Seq[String],
        launcherOptions: Seq[String],
        extraJars: Seq[os.Path]
      ): Session = {

        close()

        val dir      = TmpDir.tmpDir()
        val connFile = dir / "connection.json"

        val params      = ConnectionParameters.randomLocal()
        val connDetails = almond.protocol.Connection.fromParams(params)

        os.write(connFile, writeToArray(connDetails))

        val (jarLauncher0, launcher0) =
          if (launcherOptions.isEmpty)
            (jarLauncher, launcher)
          else
            generateLauncher(launcherOptions)

        val baseCmd: os.Shellable =
          if (extraJars.isEmpty) launcher0
          else
            Seq[os.Shellable](
              "java",
              "-cp",
              (extraJars.map(_.toString) :+ jarLauncher0).mkString(File.pathSeparator),
              "coursier.bootstrap.launcher.ResourcesLauncher"
            )

        proc = os.proc(
          baseCmd,
          "--log",
          "debug",
          "--color=false",
          "--connection-file",
          connFile,
          options
        ).spawn(cwd = dir, stdin = os.Inherit, stdout = os.Inherit)

        val conn = params.channels(
          bind = false,
          threads,
          TestLogging.logCtx
        ).unsafeRunSync()

        conn.open.unsafeRunSync()

        val sess = session(conn)
        sessions = sess :: sessions
        sess
      }

      def close(): Unit = {
        sessions.foreach(_.close())
        sessions = Nil
        if (proc != null) {
          if (proc.isAlive()) {
            proc.close()
            proc.waitFor(3.seconds.toMillis)
            if (proc.isAlive())
              proc.destroyForcibly()
          }
          proc = null
        }
      }
    }

  def withKernel[T](f: Runner => T): T = {

    var runner0: Runner with AutoCloseable = null

    try {
      runner0 = runner()
      f(runner())
    }
    finally
      runner0.close()
  }

}
