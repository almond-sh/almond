import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`

import $file.deps, deps.{Deps, ScalaVersions}
import $file.jupyterserver, jupyterserver.jupyterServer
import $file.scripts.website.Website, Website.Relativize
import $file.settings, settings.{AlmondModule, AlmondRepositories, BootstrapLauncher, DependencyListResource, ExternalSources, HasTests, PropertyFile, Util}

import mill._, scalalib._
import scala.concurrent.duration._

// Tell mill modules are under modules/
implicit def millModuleBasePath: define.BasePath =
  define.BasePath(super.millModuleBasePath.value / "modules")

class Logger(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def ivyDeps = Agg(
    Deps.scalaReflect(scalaVersion())
  )
  object test extends Tests
}

class Channels(val crossScalaVersion: String) extends AlmondModule with HasTests {
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
    Deps.jsoniterScalaCore
  )
  def compileIvyDeps = Agg(
    Deps.jsoniterScalaMacros.withConfiguration("provided")
  )
  object test extends Tests
}

class InterpreterApi(val crossScalaVersion: String) extends AlmondModule

class Interpreter(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def moduleDeps = Seq(
    shared.`interpreter-api`(),
    shared.protocol()
  )
  def ivyDeps = Agg(
    Deps.collectionCompat,
    Deps.scalatags,
    Deps.slf4jNop
  )
  object test extends Tests
}

class Kernel(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def moduleDeps = Seq(
    shared.interpreter()
  )
  def ivyDeps = Agg(
    Deps.caseAppAnnotations,
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

class JupyterApi(val crossScalaVersion: String) extends AlmondModule {
  def moduleDeps = Seq(
    shared.`interpreter-api`()
  )
  def ivyDeps = Agg(
    Deps.jvmRepr
  )
}

class ScalaKernelApi(val crossScalaVersion: String) extends AlmondModule with DependencyListResource with ExternalSources with PropertyFile {
  def crossFullScalaVersion = true
  def moduleDeps = Seq(
    shared.`interpreter-api`(),
    scala0.`jupyter-api`()
  )
  def ivyDeps = Agg(
    Deps.ammoniteCompiler,
    Deps.ammoniteReplApi,
    Deps.jvmRepr
  )
  def propertyFilePath = "almond/almond.properties"
  def propertyExtra = Seq(
    "default-scalafmt-version" -> Deps.scalafmtDynamic.dep.version
  )
}

class ScalaInterpreter(val crossScalaVersion: String) extends AlmondModule with HasTests {
  def crossFullScalaVersion = true
  def moduleDeps = Seq(
    shared.interpreter(),
    scala0.`scala-kernel-api`()
  )
  def addMetabrowse = T{
    val sv = scalaVersion()
    val patch = sv
      .split('.')
      .drop(2)
      .headOption
      .flatMap(s => scala.util.Try(s.takeWhile(_.isDigit).toInt).toOption)
    (sv.startsWith("2.12.") && patch.exists(_ <= 10)) ||
      (sv.startsWith("2.13.") && patch.exists(_ <= 1))
  }
  def ivyDeps = T{
    val metabrowse =
      if (addMetabrowse()) Agg(Deps.metabrowseServer)
      else Agg.empty
    metabrowse ++ Agg(
      Deps.coursier,
      Deps.coursierApi,
      Deps.directories,
      Deps.jansi,
      Deps.ammoniteCompiler,
      Deps.ammoniteRepl
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
        if (crossScalaVersion.startsWith("2.12.")) Seq(scala0.`almond-rx`())
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
    scala0.`scala-interpreter`()
  )
  def ivyDeps = Agg(
    Deps.caseApp,
    Deps.scalafmtDynamic
  )
  object test extends Tests {
    def moduleDeps = super.moduleDeps ++ Seq(
      scala0.`scala-interpreter`().test
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
    scala0.`scala-kernel-api`().transitiveJars() ++
      scala0.`scala-kernel-api`().unmanagedClasspath() ++
      scala0.`scala-kernel-api`().resolvedRunIvyDeps() ++
      scala0.`scala-kernel-api`().transitiveSourceJars() ++
      scala0.`scala-kernel-api`().externalSources()
}

class AlmondSpark(val crossScalaVersion: String) extends AlmondModule {
  def compileModuleDeps = Seq(
    scala0.`scala-kernel-api`()
  )
  def ivyDeps = Agg(
    Deps.ammoniteSpark,
    Deps.jsoniterScalaCore
  )
  def compileIvyDeps = Agg(
    Deps.ammoniteReplApi,
    Deps.jsoniterScalaMacros,
    Deps.sparkSql
  )
  // TODO?
  // sources.in(Compile, doc) := Nil
}

class AlmondRx(val crossScalaVersion: String) extends AlmondModule {
  def compileModuleDeps = Seq(
    scala0.`scala-kernel-api`()
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
  object logger            extends Cross[Logger]        (ScalaVersions.binaries: _*)
  object channels          extends Cross[Channels]      (ScalaVersions.binaries: _*)
  object protocol          extends Cross[Protocol]      (ScalaVersions.binaries: _*)
  object `interpreter-api` extends Cross[InterpreterApi](ScalaVersions.binaries: _*)
  object interpreter       extends Cross[Interpreter]   (ScalaVersions.binaries: _*)
  object kernel            extends Cross[Kernel]        (ScalaVersions.binaries: _*)
  object test              extends Cross[Test]          (ScalaVersions.binaries: _*)
}

// FIXME Can't use 'scala' because of macro hygiene issues in some mill macros
object scala0 extends Module {
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
    scala0.`scala-kernel-api`(scalaVersion0)
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

    val ver = scala0.`scala-kernel-api`(scalaVersion0).publishVersion()
    val latestRelease = settings.latestTaggedVersion
    val ammVer = Deps.ammoniteReplApi.dep.version
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

def jupyter(args: String*) = {
  val (sv, args0) = args match {
    case Seq(sv, rem @ _*) if sv.startsWith("2.") || sv.startsWith("3.") =>
      (sv, rem)
    case _ => (ScalaVersions.scala213, args)
  }
  T.command {
    val jupyterDir = T.ctx().dest / "jupyter"
    val launcher = scala0.`scala-kernel`(sv)
      .launcher()
      .path
      .toNIO
    jupyterServer(launcher, jupyterDir.toNIO, args0)
  }
}

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
def scalaVersions() = T.command {
  for (sv <- ScalaVersions.all)
    println(sv)
}

def launcher(scalaVersion: String = ScalaVersions.scala213) = T.command {
  val launcher = scala0.`scala-kernel`(scalaVersion).launcher().path.toNIO
  println(launcher)
}
