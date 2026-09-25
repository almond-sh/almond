package almond.proxytests

import scala.concurrent.duration.DurationInt
import scala.util.Try

/** Installs Almond and runs a notebook with it, from containers that can't reach Maven Central
  * directly: the only way out is an authenticated HTTP proxy, or a repository mirror, that the
  * tests set up and configure Almond to use, the way the "Proxies and mirrors" page of the
  * documentation says to.
  *
  * The kernels are installed like the documentation says to, for both launchers, and the notebook
  * is run with `jupyter nbconvert`, like the example notebooks are. Each run starts from an empty
  * coursier cache, so that everything the kernel needs goes through the proxy or mirror.
  */
class ProxyTests extends munit.FunSuite {

  import ProxyTestProperties._

  // Installing a kernel downloads a few hundred MBs through the proxy or mirror
  override def munitTimeout = 30.minutes

  // These tests need Docker: skip them when it's not around, except on CI, where failing is better
  // than a missing Docker silently disabling them
  override def munitIgnore = !Docker.available && System.getenv("CI") == null

  private def kernelId = "almond"

  private def alpineImage = "alpine:3.21.2"
  private def mirrorImage = "nginx:1.27-alpine"

  /** The image the kernels are installed and run in */
  private lazy val runnerImage = withTmpDir { contextDir =>
    // The build context: the Dockerfile, and the uv project that provides Jupyter to the example
    // notebooks
    os.copy(dockerDir / "runner" / "Dockerfile", contextDir / "Dockerfile")
    os.copy(examplesDir / "pyproject.toml", contextDir / "pyproject.toml")
    os.copy(examplesDir / "uv.lock", contextDir / "uv.lock")
    Docker.ensureImage(
      "almond-proxy-tests-runner",
      contextDir,
      Map("CS_VERSION" -> csVersion, "UV_VERSION" -> uvVersion)
    )
  }

  private lazy val proxyImage =
    Docker.ensureImage("almond-proxy-tests-proxy", dockerDir / "authenticated-proxy", Map.empty)

  private def withTmpDir[T](f: os.Path => T): T = {
    os.makeDir.all(tmpDir)
    val dir = os.temp.dir(tmpDir, prefix = "proxy-tests-")
    try f(dir)
    finally
      try os.remove.all(dir)
      catch {
        case e: java.io.IOException =>
          System.err.println(s"Ignoring $e while removing $dir")
      }
  }

  /** The user running the tests - the files the containers write under our temporary directories
    * are handed back to it, for us to be able to remove them
    */
  private lazy val (hostUid, hostGid) = {
    def id(opt: String) = Try(os.proc("id", opt).call().out.trim()).getOrElse("0")
    (id("-u"), id("-g"))
  }

  private def mavenSnapshots = "https://central.sonatype.com/repository/maven-snapshots"

  /** Where the local repository with the Almond modules under test is mounted in the runner */
  private def localRepoInContainer = "file:///local-repo"

  /** Repositories the kernels resolve from: the defaults, plus the local repository */
  private def kernelRepositories =
    (Seq("ivy2Local", "central") ++
      (if (useMavenSnapshots) Seq(mavenSnapshots) else Nil) ++
      Seq(localRepoInContainer)).mkString("|")

  /** The same, for the cs command generating the launchers */
  private def csRepositoryArgs: Seq[String] =
    Seq("-r", localRepoInContainer) ++
      (if (useMavenSnapshots) Seq("-r", mavenSnapshots) else Nil) ++
      Seq("-r", "jitpack")

  private def shellQuote(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"
  private def cmd(args: os.Shellable*): String =
    args.flatMap(_.value).map(shellQuote).mkString(" ")

  /** How a kernel gets installed: the commands of the installation pages of the documentation, run
    * from the directory the kernel gets installed from
    */
  sealed abstract class Launcher(val name: String) {

    /** Shell commands generating a launcher named `almond` in the current directory, and installing
      * the kernel with it
      */
    def installCommands: Seq[String]
  }
  object Launcher {
    private def installArgs = Seq(
      "--install",
      "--jupyter-path",
      "/data/jupyter/kernels",
      "--id",
      kernelId,
      // Jupyter passes these to the kernel: the modules under test are only in the local
      // repository, that the launcher and the kernel would not look into otherwise
      "--env",
      s"COURSIER_REPOSITORIES=$kernelRepositories"
    )

    /** The newer launcher, that resolves the kernel for the requested Scala version at startup */
    case object Newer extends Launcher("launcher") {
      def installCommands = Seq(
        cmd(
          "cs",
          "bootstrap",
          csRepositoryArgs,
          s"sh.almond:launcher_3:$almondVersion",
          "--main-class",
          launcherMainClass,
          "-o",
          "almond"
        ),
        cmd("./almond", installArgs, "--scala", scalaVersion)
      )
    }

    /** The former launcher, that has the whole kernel class path embedded */
    case object Former extends Launcher("former launcher") {
      def installCommands = {
        val forcedModules =
          if (scalaVersion.startsWith("2."))
            Seq("scala-library", "scala-compiler", "scala-reflect")
          else
            Seq("scala-library", "scala3-library_3", "scala3-compiler_3")
        val forcedVersionArgs = forcedModules.flatMap { name =>
          Seq("--force-version", s"org.scala-lang:$name:$scalaVersion")
        }
        Seq(
          cmd(
            "cs",
            "bootstrap",
            csRepositoryArgs,
            s"sh.almond::scala-kernel:$almondVersion",
            "--shared",
            "sh.almond::scala-kernel-api",
            "--scala",
            scalaVersion,
            forcedVersionArgs,
            "-o",
            "almond"
          ),
          cmd("./almond", installArgs)
        )
      }
    }
  }

  /** What stands between the kernels and Maven Central */
  sealed abstract class Setup(val name: String) {

    /** Image of the server, that listens on port 80 */
    def image: String

    /** Extra `docker run` arguments for the server */
    def serverArgs: Seq[os.Shellable] = Nil

    /** Writes the configuration making Almond use the server at `ip`, under the home directory of
      * the kernels
      */
    def configure(home: os.Path, ip: String): Unit

    /** A configuration that must not let the kernel through, with the message expected in the
      * output when it's used
      */
    def misconfigure: Option[((os.Path, String) => Unit, String)] = None
  }

  /** An authenticated HTTP proxy, that the JVMs of Almond pick up from the Maven settings file */
  case object Proxy extends Setup("authenticated proxy") {
    def image = proxyImage
    private def settings(ip: String, password: String) =
      s"""<settings>
         |  <proxies>
         |    <proxy>
         |      <id>almond-proxy-tests</id>
         |      <active>true</active>
         |      <protocol>http</protocol>
         |      <host>$ip</host>
         |      <port>80</port>
         |      <username>jack</username>
         |      <password>$password</password>
         |    </proxy>
         |  </proxies>
         |</settings>
         |""".stripMargin
    def configure(home: os.Path, ip: String) =
      os.write.over(home / ".m2" / "settings.xml", settings(ip, "insecure"), createFolders = true)
    override def misconfigure = Some(
      (
        (home, ip) =>
          os.write.over(home / ".m2" / "settings.xml", settings(ip, "wrong"), createFolders = true),
        "407 Proxy Authentication Required"
      )
    )
  }

  /** A repository mirror, that the coursier of Almond picks up from its configuration directory */
  case object Mirror extends Setup("mirror") {
    def image = mirrorImage
    override def serverArgs = Seq(
      "-v",
      s"${dockerDir / "mirror" / "nginx.conf"}:/etc/nginx/conf.d/default.conf:ro"
    )
    def configure(home: os.Path, ip: String) = {
      val properties =
        s"""central.from=https://repo1.maven.org/maven2
           |central.to=http://$ip/maven2
           |central.type=maven
           |
           |snapshots.from=$mavenSnapshots
           |snapshots.to=http://$ip/maven-snapshots
           |snapshots.type=maven
           |
           |jitpack.from=https://jitpack.io
           |jitpack.to=http://$ip/jitpack
           |jitpack.type=maven
           |""".stripMargin
      os.write.over(
        home / ".config" / "coursier" / "mirror.properties",
        properties,
        createFolders = true
      )
    }
  }

  /** The notebook the kernels run: it prints something, and pulls a dependency that isn't on the
    * kernel class path, that has to go through the proxy or mirror too
    */
  private def notebook: String = {
    def cell(source: String) = ujson.Obj(
      "cell_type"       -> "code",
      "execution_count" -> ujson.Null,
      "metadata"        -> ujson.Obj(),
      "outputs"         -> ujson.Arr(),
      "source"          -> source
    )
    ujson.Obj(
      "cells" -> ujson.Arr(
        cell("""println("Hello from almond")"""),
        cell("import $ivy.`org.typelevel::cats-core:2.12.0`"),
        cell("cats.Show[Int].show(42)")
      ),
      "metadata"       -> ujson.Obj(),
      "nbformat"       -> 4,
      "nbformat_minor" -> 4
    ).render(indent = 1)
  }

  /** Runs `script` in the runner image, on the isolated network, with `dir` mounted at `/data`,
    * `dir / "home"` as home directory, and the local repository at `/local-repo`
    */
  private def runScript(networks: Docker.Networks, dir: os.Path, script: String): (Int, String) = {
    val scriptFile = dir / "run.sh"
    os.write.over(scriptFile, script)
    Docker.runIsolated(
      networks,
      Seq[os.Shellable](
        "-v",
        s"$dir:/data",
        "-v",
        s"${dir / "home"}:/root",
        "-v",
        s"$localRepo:/local-repo:ro",
        "-e",
        s"HOST_UID=$hostUid",
        "-e",
        s"HOST_GID=$hostGid"
      ),
      runnerImage,
      Seq[os.Shellable]("bash", "/data/run.sh")
    )
  }

  /** Shell script running `commands` from `/data`, and handing back the files it creates to the
    * user running the tests
    */
  private def script(commands: Seq[String]): String =
    s"""#!/usr/bin/env bash
       |set -e
       |trap 'chown -R "$$HOST_UID:$$HOST_GID" /data /root || true' EXIT
       |export JUPYTER_PATH=/data/jupyter
       |cd /data
       |${commands.mkString(System.lineSeparator())}
       |""".stripMargin

  private def textOf(value: ujson.Value): String =
    value.strOpt.getOrElse(value.arr.map(_.str).mkString)

  private def installAndRun(setup: Setup, launcher: Launcher): Unit =
    Docker.withNetworks { networks =>
      Docker.withServer(setup.image, networks, 80, setup.serverArgs*) { server =>
        withTmpDir { dir =>
          val ip   = server.ipOn(networks.internal)
          val home = dir / "home"
          os.makeDir.all(home)

          // Check that the server doesn't let the kernel through with a wrong configuration first:
          // a proxy that would let everything through would make the tests pass for nothing
          for ((misconfigure, expectedMessage) <- setup.misconfigure) {
            misconfigure(home, ip)
            val (exitCode, output) =
              runScript(networks, dir, script(launcher.installCommands.take(1)))
            assertNotEquals(exitCode, 0, "Generating the launcher should have failed")
            assert(
              output.contains(expectedMessage),
              s"Expected '$expectedMessage' in the output of the launcher generation"
            )
          }

          setup.configure(home, ip)
          os.write.over(dir / "notebook.ipynb", notebook)
          val runNotebook = cmd(
            "jupyter",
            "nbconvert",
            "--to",
            "notebook",
            "--execute",
            s"--ExecutePreprocessor.kernel_name=$kernelId",
            // Starting the kernel and running its cells downloads things, give them time
            "--ExecutePreprocessor.startup_timeout=900",
            "--ExecutePreprocessor.timeout=900",
            "--output-dir=/data",
            "--output=output.ipynb",
            "/data/notebook.ipynb"
          )
          val commands =
            Seq("echo '--- Installing the kernel'") ++
              launcher.installCommands ++
              Seq(
                "echo '--- Kernel spec'",
                s"cat /data/jupyter/kernels/$kernelId/kernel.json",
                "echo",
                "echo '--- Running the notebook'",
                runNotebook
              )
          val (exitCode, _) = runScript(networks, dir, script(commands))
          assertEquals(exitCode, 0, "Installing the kernel or running the notebook failed")

          val output  = ujson.read(os.read(dir / "output.ipynb"))
          val outputs = output("cells").arr.flatMap(_.obj.get("outputs").toSeq.flatMap(_.arr))
          val errors  = outputs.filter(_("output_type").str == "error")
          assert(
            errors.isEmpty,
            s"Some cells failed: ${errors.map(_.render(indent = 1)).mkString(System.lineSeparator())}"
          )
          val text = outputs
            .flatMap { output =>
              output("output_type").str match {
                case "stream"                          => Seq(output("text"))
                case "execute_result" | "display_data" => output("data").obj.get("text/plain").toSeq
                case _                                 => Nil
              }
            }
            .map(textOf)
            .mkString(System.lineSeparator())
          assert(text.contains("Hello from almond"), s"Unexpected notebook output: $text")
          assert(text.contains("\"42\""), s"Unexpected notebook output: $text")
        }
      }
    }

  test("Maven Central is reachable from the regular network only") {
    Docker.withNetworks { networks =>
      def reachable(network: String): Boolean =
        os.proc(
          "docker",
          "run",
          "--rm",
          "--network",
          network,
          alpineImage,
          "wget",
          "-q",
          "-T",
          "15",
          "-O",
          "/dev/null",
          "https://repo1.maven.org/maven2/"
        )
          .call(check = false, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
          .exitCode == 0
      assert(
        reachable(networks.external),
        "Maven Central should be reachable from the regular network"
      )
      assert(
        !reachable(networks.internal),
        "Maven Central should not be reachable from the isolated network"
      )
    }
  }

  for (setup <- Seq(Proxy, Mirror); launcher <- Seq(Launcher.Newer, Launcher.Former))
    test(s"${setup.name} (${launcher.name})") {
      installAndRun(setup, launcher)
    }
}
