import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`
import $ivy.`com.github.lolgab::mill-mima_mill0.9:0.0.4`

import $file.project.deps, deps.{Deps, DepOps, ScalaVersions}
import $file.project.jupyterserver, jupyterserver.jupyterServer
import $file.scripts.website.Website, Website.Relativize
import $file.project.settings, settings.{AlmondModule, AlmondRepositories, BootstrapLauncher, DependencyListResource, ExternalSources, HasTests, Mima, PropertyFile, Util}

import java.nio.charset.Charset
import java.nio.file.FileSystems

import mill._, scalalib._
import _root_.scala.concurrent.duration._
import _root_.scala.util.Properties

// Tell mill modules are under modules/
implicit def millModuleBasePath: define.BasePath =
  define.BasePath(super.millModuleBasePath.value / "modules")

class LoggerScala2Macros(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def ivyDeps = T{
    val sv = scalaVersion()
    Agg(Deps.scalaReflect(sv))
  }
}

class Logger(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def supports3 = true
  def moduleDeps = Seq(
    shared.`logger-scala2-macros`()
  )
  def ivyDeps = T{
    val sv = scalaVersion()
    val scalaReflect =
      if (sv.startsWith("2.")) Agg(Deps.scalaReflect(sv))
      else Agg(ivy"org.scala-lang:scala3-library_3:${scalaVersion()}")
    scalaReflect
  }
  object test extends Tests
}

class Channels(val crossScalaVersion: String) extends AlmondModule with HasTests with Mima {
  def moduleDeps = Seq(
    shared.logger()
  )
  def ivyDeps = Agg(
    Deps.fs2,
    Deps.jeromq
  )
  object test extends Tests
}

class Protocol(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def moduleDeps = Seq(
    shared.channels()
  )
  def ivyDeps = Agg(
    Deps.jsoniterScalaCore.applyBinaryVersion213_3(scalaVersion())
  )
  def compileIvyDeps = Agg(
    Deps.scalaReflect(scalaVersion()),
    Deps.jsoniterScalaMacros.withConfiguration("provided")
  )
  object test extends Tests
}

class InterpreterApi(val crossScalaVersion: String) extends AlmondModule with Mima

class Interpreter(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def moduleDeps = Seq(
    shared.`interpreter-api`(),
    shared.protocol()
  )
  def ivyDeps = Agg(
    Deps.collectionCompat,
    Deps.scalatags.applyBinaryVersion213_3(scalaVersion()),
    Deps.slf4jNop
  )
  object test extends Tests
}

class Kernel(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def moduleDeps = Seq(
    shared.interpreter()
  )
  def ivyDeps = Agg(
    Deps.caseAppAnnotations.withDottyCompat(scalaVersion(), ScalaVersions.cross2_3Version),
    Deps.collectionCompat,
    Deps.fs2
  )
  object test extends Tests {
    def moduleDeps = super.moduleDeps ++ Seq(
      shared.interpreter().test
    )
  }
}

class Test(val crossScalaVersion: String) extends AlmondModule {
  def moduleDeps = Seq(
    shared.`interpreter-api`()
  )
}

class JupyterApi(val crossScalaVersion: String) extends AlmondModule with Mima {
  def moduleDeps = Seq(
    shared.`interpreter-api`()
  )
  def ivyDeps = Agg(
    Deps.jvmRepr
  )
}

class ScalaKernelApi(val crossScalaVersion: String) extends AlmondModule with DependencyListResource with ExternalSources with PropertyFile with Mima {
  def crossFullScalaVersion = true
  def moduleDeps = Seq(
    shared.`interpreter-api`(),
    scala.`jupyter-api`()
  )
  def ivyDeps = Agg(
    Deps.ammoniteCompiler(crossScalaVersion),
    Deps.ammoniteReplApi(crossScalaVersion),
    Deps.jvmRepr
  )
  def propertyFilePath = "almond/almond.properties"
  def propertyExtra = Seq(
    "default-scalafmt-version" -> Deps.scalafmtDynamic.dep.version,
    "scala-version" -> crossScalaVersion
  )
}

class ScalaInterpreter(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def crossFullScalaVersion = true
  def supports3 = true
  def moduleDeps = Seq(
    shared.interpreter(),
    scala.`scala-kernel-api`()
  )
  def addMetabrowse = T{
    val sv = scalaVersion()
    val patch = sv
      .split('.')
      .drop(2)
      .headOption
      .flatMap(s => _root_.scala.util.Try(s.takeWhile(_.isDigit).toInt).toOption)
    (sv.startsWith("2.12.") && patch.exists(_ <= 10)) ||
      (sv.startsWith("2.13.") && patch.exists(_ <= 1))
  }
  def ivyDeps = T{
    val metabrowse =
      if (addMetabrowse()) Agg(Deps.metabrowseServer)
      else Agg.empty
    metabrowse ++ Agg(
      Deps.coursier.withDottyCompat(scalaVersion(), ScalaVersions.cross2_3Version),
      Deps.coursierApi,
      Deps.directories,
      Deps.jansi,
      Deps.ammoniteCompiler(crossScalaVersion),
      Deps.ammoniteRepl(crossScalaVersion)
    )
  }
  def sources = T.sources {
    val dirName =
      if (addMetabrowse()) "scala-has-metabrowse"
      else "scala-no-metabrowse"
    val extra = PathRef(millSourcePath / "src" / "main" / dirName)
    super.sources() ++ Seq(extra)
  }
  object test extends Tests {
    def moduleDeps = {
      val rx =
        if (crossScalaVersion.startsWith("2.12.")) Seq(scala.`almond-rx`())
        else Nil
      super.moduleDeps ++
        Seq(shared.kernel().test) ++
        rx
    }
  }
}

class ScalaKernel(val crossScalaVersion: String) extends AlmondModule with HasTests with ExternalSources with BootstrapLauncher {
  def crossFullScalaVersion = true
  def moduleDeps = Seq(
    shared.kernel(),
    scala.`scala-interpreter`()
  )
  def ivyDeps = Agg(
    Deps.caseApp,
    Deps.scalafmtDynamic
  )
  object test extends Tests {
    def moduleDeps = super.moduleDeps ++ Seq(
      scala.`scala-interpreter`().test
    )
  }

  def runClasspath =
    super.runClasspath() ++
      transitiveSources() ++
      externalSources()
  def launcherClassPath =
    transitiveJars() ++
      unmanagedClasspath() ++
      resolvedRunIvyDeps() ++
      transitiveSourceJars() ++
      externalSources()
  def launcherSharedClassPath =
    scala.`scala-kernel-api`().transitiveJars() ++
      scala.`scala-kernel-api`().unmanagedClasspath() ++
      scala.`scala-kernel-api`().resolvedRunIvyDeps() ++
      scala.`scala-kernel-api`().transitiveSourceJars() ++
      scala.`scala-kernel-api`().externalSources()

  def manifest = T{
    import java.util.jar.Attributes.Name
    val ver = publishVersion()
    super.manifest().add(
      Name.IMPLEMENTATION_TITLE.toString -> "scala-kernel",
      Name.IMPLEMENTATION_VERSION.toString -> ver,
      Name.SPECIFICATION_VENDOR.toString -> "sh.almond",
      Name.SPECIFICATION_TITLE.toString -> "scala-kernel",
      Name.IMPLEMENTATION_VENDOR_ID.toString -> "sh.almond",
      Name.SPECIFICATION_VERSION.toString -> ver,
      Name.IMPLEMENTATION_VENDOR.toString -> "sh.almond"
    )
  }
}

class AlmondSpark(val crossScalaVersion: String) extends AlmondModule with Mima {
  def compileModuleDeps = Seq(
    scala.`scala-kernel-api`()
  )
  def ivyDeps = Agg(
    Deps.ammoniteSpark,
    Deps.jsoniterScalaCore
  )
  def compileIvyDeps = Agg(
    Deps.ammoniteReplApi(crossScalaVersion),
    Deps.jsoniterScalaMacros,
    Deps.sparkSql
  )
  // TODO?
  // sources.in(Compile, doc) := Nil
}

class AlmondRx(val crossScalaVersion: String) extends AlmondModule with Mima {
  def compileModuleDeps = Seq(
    scala.`scala-kernel-api`()
  )
  def ivyDeps = Agg(
    Deps.scalaRx
  )
}

class Echo(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def moduleDeps = Seq(
    shared.kernel()
  )
  def ivyDeps = Agg(
    Deps.caseApp
  )
  def propertyFilePath = "almond/echo.properties"
  object test extends Tests {
    def moduleDeps = super.moduleDeps ++ Seq(
      shared.test()
    )
  }
}

object shared extends Module {
  object `logger-scala2-macros` extends Cross[LoggerScala2Macros](ScalaVersions.binaries: _*)
  object logger            extends Cross[Logger]        (ScalaVersions.binaries: _*)
  object channels          extends Cross[Channels]      (ScalaVersions.binaries: _*)
  object protocol          extends Cross[Protocol]      (ScalaVersions.binaries: _*)
  object `interpreter-api` extends Cross[InterpreterApi](ScalaVersions.binaries: _*)
  object interpreter       extends Cross[Interpreter]   (ScalaVersions.binaries: _*)
  object kernel            extends Cross[Kernel]        (ScalaVersions.binaries: _*)
  object test              extends Cross[Test]          (ScalaVersions.binaries: _*)
}

// FIXME Can't use 'scala' because of macro hygiene issues in some mill macros
object scala extends Module {
  implicit def millModuleBasePath: define.BasePath =
    define.BasePath(super.millModuleBasePath.value / os.up / "scala")
  object `jupyter-api`       extends Cross[JupyterApi]      (ScalaVersions.binaries: _*)
  object `scala-kernel-api`  extends Cross[ScalaKernelApi]  (ScalaVersions.all: _*)
  object `scala-interpreter` extends Cross[ScalaInterpreter](ScalaVersions.all: _*)
  object `scala-kernel`      extends Cross[ScalaKernel]     (ScalaVersions.all: _*)
  object `almond-spark`      extends Cross[AlmondSpark]     (ScalaVersions.scala212)
  object `almond-rx`         extends Cross[AlmondRx]        (ScalaVersions.scala212)
}

object echo extends Cross[Echo](ScalaVersions.binaries: _*)

object docs extends ScalaModule with AlmondRepositories {
  private def scalaVersion0 = ScalaVersions.scala213
  def scalaVersion = scalaVersion0
  def moduleDeps = Seq(
    scala.`scala-kernel-api`(scalaVersion0)
  )
  def ivyDeps = Agg(
    Deps.mdoc
  )
  def mainClass = Some("mdoc.Main")
  def generate(args: String*) = T.command {

    def processArgs(
      npmInstall: Boolean,
      yarnRunBuild: Boolean,
      watch: Boolean,
      relativize: Boolean,
      args: List[String]
    ): (Boolean, Boolean, Boolean, Boolean, List[String]) =
      args match {
        case "--npm-install"    :: rem => processArgs(true,       yarnRunBuild, watch, relativize, rem)
        case "--yarn-run-build" :: rem => processArgs(npmInstall, true,         watch, relativize, rem)
        case "--watch"          :: rem => processArgs(npmInstall, yarnRunBuild, true,  relativize, rem)
        case "--relativize"     :: rem => processArgs(npmInstall, yarnRunBuild, watch, true,       rem)
        case _ => (npmInstall, yarnRunBuild, watch, relativize, args)
      }
    val (npmInstall, yarnRunBuild, watch, relativize, args0) = processArgs(false, false, false, false, args.toList)

    val ver = scala.`scala-kernel-api`(scalaVersion0).publishVersion()
    val latestRelease = settings.latestTaggedVersion
    val ammVer = Deps.ammoniteReplApi(scalaVersion0).dep.version
    val scalaVer = scalaVersion0

    val isSnapshot = ver.endsWith("SNAPSHOT")
    val extraSbt =
      if (isSnapshot) """resolvers += Resolver.sonatypeRepo("snapshots")""" + "\n"
      else ""
    val extraCoursierArgs =
      if (isSnapshot) "-r sonatype:snapshots "
      else ""

    val outputDir = "docs/processed-pages"

    val allArgs = Seq(
      "--in", "docs/pages",
      "--out", outputDir,
      "--site.VERSION", ver,
      "--site.LATEST_RELEASE", latestRelease,
      "--site.EXTRA_SBT", extraSbt,
      "--site.AMMONITE_VERSION", ammVer,
      "--site.SCALA_VERSION", scalaVer,
      "--site.EXTRA_COURSIER_ARGS", extraCoursierArgs
    ) ++ (if (watch) Seq("--watch") else Nil) ++ args0

    // TODO Run yarn run thing right after, add --watch mode

    val websiteDir = os.pwd / "docs" / "website"

    if (npmInstall)
      Util.run(Seq("npm", "install"), dir = websiteDir.toIO)

    def runMdoc(): Unit =
      // adapted from https://github.com/com-lihaoyi/mill/blob/c500ca986ab79af3ce59ba65a093146672092307/scalalib/src/JavaModule.scala#L488-L494
      mill.modules.Jvm.runSubprocess(
        finalMainClass(),
        runClasspath().map(_.path),
        Nil,
        forkEnv(),
        allArgs,
        workingDir = forkWorkingDir()
      )

    if (watch) {
      if (yarnRunBuild)
        Util.withBgProcess(
          Seq("yarn", "run", "start"),
          dir = websiteDir.toIO,
          waitFor = () => Util.waitForDir((os.pwd / outputDir.split('/').toSeq).toIO)
        ) {
          runMdoc()
        }
      else
        runMdoc()
    } else {
      runMdoc()
      if (yarnRunBuild)
        Util.run(Seq("yarn", "run", "build"), dir = websiteDir.toIO)
      if (relativize)
        Relativize.relativize((websiteDir / "build").toNIO)
    }
  }
}

def jupyter0(args: Seq[String], fast: Boolean) = {
  val (sv, args0) = args match {
    case Seq(sv, rem @ _*) if sv.startsWith("2.") || sv.startsWith("3.") =>
      (sv, rem)
    case _ => (ScalaVersions.scala213, args)
  }
  val launcher =
    if (fast) scala.`scala-kernel`(sv).fastLauncher
    else scala.`scala-kernel`(sv).launcher
  T.command {
    val jupyterDir = T.ctx().dest / "jupyter"
    val launcher0 = launcher().path.toNIO
    jupyterServer(launcher0, jupyterDir.toNIO, args0)
  }
}

def jupyter(args: String*) =
  jupyter0(args, fast = false)

def jupyterFast(args: String*) =
  jupyter0(args, fast = true)

def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) =
  T.command {
    val timeout = 10.minutes
    val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
    val pgpPassword = sys.env("PGP_PASSWORD")
    val data = define.Task.sequence(tasks.value)()

    settings.publishSonatype(
      credentials = credentials,
      pgpPassword = pgpPassword,
      data = data,
      timeout = timeout,
      log = T.ctx().log
    )
  }

def scala212() = T.command {
  println(ScalaVersions.scala212)
}
def scala213() = T.command {
  println(ScalaVersions.scala213)
}
def scala3() = T.command {
  println(ScalaVersions.scala3)
}
def scalaVersions() = T.command {
  for (sv <- ScalaVersions.all)
    println(sv)
}

def launcher(scalaVersion: String = ScalaVersions.scala213) = T.command {
  val launcher = scala.`scala-kernel`(scalaVersion).launcher().path.toNIO
  println(launcher)
}

private val examplesDir = os.pwd / "examples"
def exampleNotebooks = T.sources {
  os.list(examplesDir)
    .filter(_.last.endsWith(".ipynb"))
    .filter(os.isFile(_))
    .map(PathRef(_))
}

def validateExamples(matcher: String = "") = {
  val sv = "2.12.12"
  val kernelId = "almond-sources-tmp"
  val baseRepoRoot = os.rel / "out" / "repo"

  def maybeEscapeArg(arg: String): String =
    if (Properties.isWin && arg.exists(c => c == ' ' || c == '\"'))
      "\"" + arg.replace("\"", "\\\"") + "\""
    else arg

  val pathMatcherOpt =
    if (matcher.trim.isEmpty) None
    else {
      val m = FileSystems.getDefault.getPathMatcher("glob:" + matcher.trim)
      Some(m)
    }

  T.command {
    val launcher = scala.`scala-kernel`(sv).launcher().path.toNIO
    val jupyterPath = T.dest / "jupyter"
    val outputDir = T.dest / "output"
    os.makeDir.all(outputDir)

    val version = scala.`scala-kernel`(sv).publishVersion()
    val repoRoot = baseRepoRoot / version

    os.proc(
      launcher.toString,
      "--jupyter-path", jupyterPath / "kernels",
      "--id", kernelId,
      "--install", "--force",
      "--trap-output",
      "--predef-code", maybeEscapeArg("sys.props(\"almond.ids.random\") = \"0\""),
      "--extra-repository", s"ivy:${repoRoot.toNIO.toUri.toASCIIString}/[defaultPattern]"
    ).call(cwd = examplesDir)

    val nbFiles = exampleNotebooks()
      .map(_.path)
      .filter { p =>
        pathMatcherOpt.fold(true) { m =>
          m.matches(p.toNIO.getFileName)
        }
      }

    var errorCount = 0
    for (f <- nbFiles) {
      val output = outputDir / f.last
      os.proc(
        "jupyter", "nbconvert",
        "--to", "notebook",
        "--execute",
        s"--ExecutePreprocessor.kernel_name=$kernelId",
        f,
        s"--output=$output"
      ).call(cwd = examplesDir, env = Map("JUPYTER_PATH" -> jupyterPath.toString))

      if (Properties.isWin) {
        val rawOutput = os.read(output, Charset.defaultCharset())
        val updatedOutput = rawOutput.replace("\r\n", "\n").replace("\\r\\n", "\\n")
        // writing the updated notebook on disk for the diff below
        os.write.over(output, updatedOutput.getBytes(Charset.defaultCharset()))
      }

      val result = os.read(output, Charset.defaultCharset())
      val expected = os.read(f)

      if (result != expected) {
        System.err.println(s"${f.last} differs:")
        System.err.println()
        os.proc("diff", "-u", f, output).call(cwd = examplesDir)
        errorCount += 1
      }
    }

    if (errorCount != 0)
      sys.error(s"Found $errorCount error(s)")
  }
}

def launcherFast(scalaVersion: String = ScalaVersions.scala213) = T.command {
  val launcher = scala.`scala-kernel`(scalaVersion).fastLauncher().path.toNIO
  println(launcher)
}
