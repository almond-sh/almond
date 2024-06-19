import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`
import $ivy.`io.get-coursier.util::get-cs:0.1.1`
import $ivy.`com.github.lolgab::mill-mima::0.1.1`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.26`

import $file.project.deps, deps.{Deps, DepOps, ScalaVersions, Versions}
import $file.project.jupyterserver, jupyterserver.{jupyterConsole => jupyterConsole0, jupyterServer}
import $file.scripts.website0.Website, Website.Relativize
import $file.project.settings, settings.{
  AlmondModule,
  AlmondRepositories,
  AlmondSimpleModule,
  AlmondTestModule,
  AlmondUnpublishedModule,
  BootstrapLauncher,
  DependencyListResource,
  ExternalSources,
  LocalRepo,
  Mima,
  PropertyFile,
  TestCommand,
  Util,
  buildVersion
}

import java.nio.charset.Charset
import java.nio.file.FileSystems

import coursier.getcs.GetCs
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._, scalalib._
import mill.scalalib.api.ZincWorkerUtil.isScala3
import mill.contrib.bloop.Bloop
import _root_.scala.concurrent.duration._
import _root_.scala.util.Properties

// Tell mill modules are under modules/
implicit def millModuleBasePath: define.Ctx.BasePath =
  define.Ctx.BasePath(super.millModuleBasePath.value / "modules")

trait LoggerScala2Macros extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def ivyDeps = T {
    val sv = scalaVersion()
    if (isScala3(sv)) Agg.empty[Dep] else Agg(Deps.scalaReflect(sv))
  }
}

trait Logger extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def moduleDeps =
    if (isScala3(crossScalaVersion)) Seq.empty
    else
      Seq(
        shared.`logger-scala2-macros`()
      )
  def ivyDeps = T {
    val sv = scalaVersion()
    val scalaReflect =
      if (sv.startsWith("2.")) Agg(Deps.scalaReflect(sv))
      else Agg(ivy"org.scala-lang:scala3-library_3:${scalaVersion()}")
    scalaReflect
  }
  object test extends CrossSbtModuleTests with AlmondTestModule
}

trait Channels extends Cross.Module[String] with AlmondModule with Mima {
  def crossScalaVersion = crossValue
  def moduleDeps = Seq(
    shared.logger()
  )
  def ivyDeps = Agg(
    Deps.fs2,
    Deps.jeromq
  )
  object test extends CrossSbtModuleTests with AlmondTestModule
}

trait Protocol extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def moduleDeps = Seq(
    shared.channels()
  )
  def ivyDeps = Agg(
    Deps.jsoniterScalaCore
  )
  def compileIvyDeps = {
    val maybeScalaReflect =
      if (isScala3(crossScalaVersion)) Agg.empty[Dep]
      else Agg(Deps.scalaReflect(crossScalaVersion))
    maybeScalaReflect ++ Agg(
      Deps.jsoniterScalaMacros.withConfiguration("provided")
    )
  }
  object test extends CrossSbtModuleTests with AlmondTestModule
}

trait InterpreterApi extends Cross.Module[String] with AlmondModule with Mima {
  def crossScalaVersion = crossValue
}

trait Interpreter extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def moduleDeps = Seq(
    shared.`interpreter-api`(),
    shared.protocol()
  )
  def ivyDeps = Agg(
    Deps.collectionCompat,
    Deps.fansi,
    Deps.scalatags,
    Deps.slf4jNop
  )
  object test extends CrossSbtModuleTests with AlmondTestModule
}

trait Kernel extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def moduleDeps = Seq(
    shared.interpreter()
  )
  def compileIvyDeps = Agg(
    Deps.jsoniterScalaMacros
  )
  def ivyDeps = Agg(
    Deps.caseAppAnnotations,
    Deps.collectionCompat,
    Deps.coursierApi,
    Deps.fs2
  )
  object test extends CrossSbtModuleTests with AlmondTestModule {
    def moduleDeps = super.moduleDeps ++ Seq(
      shared.interpreter().test,
      shared.`test-kit`()
    )
  }
}

trait Test extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def moduleDeps = Seq(
    shared.`interpreter-api`()
  )
}

trait JupyterApi extends Cross.Module[String] with AlmondModule with Mima {
  def crossScalaVersion = crossValue
  def moduleDeps = Seq(
    shared.`interpreter-api`()
  )
  def ivyDeps = Agg(
    Deps.jvmRepr
  )
}

trait ScalaKernelApi extends Cross.Module[String] with AlmondModule with DependencyListResource
    with ExternalSources with PropertyFile with Mima with Bloop.Module {
  def crossScalaVersion     = crossValue
  def skipBloop             = !ScalaVersions.binaries.contains(crossScalaVersion)
  def crossFullScalaVersion = true
  def moduleDeps = Seq(
    shared.`interpreter-api`(),
    scala.`jupyter-api`()
  )
  def ivyDeps = Agg(
    Deps.ammoniteCompiler.exclude(("org.slf4j", "slf4j-api")),
    Deps.ammoniteReplApi.exclude(("org.slf4j", "slf4j-api")),
    Deps.jvmRepr,
    Deps.coursierApi.exclude(("org.slf4j", "slf4j-api")),
    Deps.collectionCompat
  )

  def resolvedIvyDeps = T {
    // Ensure we don't depend on slf4j-api
    // As no logger implem would be loaded alongside it by default, slf4j would fail to initialize,
    // complain in stderr, and default to NOP logging.
    val value = super.resolvedIvyDeps()
    val jarNames = value
      .map(_.path.last)
      .filter(_.endsWith(".jar"))
      .map(_.stripSuffix(".jar"))
    val slf4jJars = jarNames.filter(_.startsWith("slf4j-"))
    if (slf4jJars.nonEmpty)
      sys.error(s"Found slf4j JARs: ${slf4jJars.mkString(", ")}")
    value
  }

  def propertyFilePath = "almond/almond.properties"
  def propertyExtra = T {
    Seq(
      "default-scalafmt-version" -> Deps.scalafmtDynamic.dep.version,
      "scala-version"            -> crossScalaVersion
    )
  }
}

trait ScalaInterpreter extends Cross.Module[String] with AlmondModule with Bloop.Module {
  def crossScalaVersion     = crossValue
  def skipBloop             = !ScalaVersions.binaries.contains(crossScalaVersion)
  def crossFullScalaVersion = true
  def moduleDeps = Seq(
    shared.interpreter(),
    scala.`coursier-logger`(),
    scala.`scala-kernel-api`(),
    scala.`shared-directives`(),
    scala.`toree-hooks`(ScalaVersions.binary(crossScalaVersion))
  )
  def ivyDeps = T {
    val metabrowse =
      if (crossScalaVersion.startsWith("2."))
        Agg(
          Deps.metabrowseServer
            // don't let metabrowse bump our slf4j version (switching to v2 can be quite sensitive when Spark is involved)
            .exclude(("org.slf4j", "slf4j-api")),
          // bump the scalameta version, so that all scalameta JARs have the same version as the few scalameta
          // dependencies of Ammonite
          Deps.scalameta
        )
      else
        Agg.empty
    metabrowse ++ Agg(
      Deps.coursier.withDottyCompat(crossScalaVersion),
      Deps.coursierApi,
      Deps.dependencyInterface,
      Deps.directiveHandler,
      Deps.jansi,
      Deps.ammoniteCompiler.exclude(("net.java.dev.jna", "jna")),
      Deps.ammoniteRepl.exclude(("net.java.dev.jna", "jna"))
    )
  }
  def scalacOptions = super.scalacOptions() ++ {
    val scala213Options =
      if (scalaVersion().startsWith("2.13.")) Seq("-Ymacro-annotations")
      else Nil
    scala213Options
  }
  def sources = T.sources {
    super.sources() ++ CrossSources.extraSourcesDirs(scalaVersion(), millSourcePath)
  }
  object test extends CrossSbtModuleTests with AlmondTestModule {
    def moduleDeps = {
      val rx =
        if (crossScalaVersion.startsWith("2.12.")) Seq(scala.`almond-rx`())
        else Nil
      super.moduleDeps ++ rx ++ Seq(
        shared.`test-kit`(),
        shared.kernel().test,
        scala.`test-definitions`()
      )
    }
    def ivyDeps = super.ivyDeps() ++ Seq(
      Deps.caseApp
    )
  }
}

trait ScalaKernel extends Cross.Module[String] with AlmondModule with ExternalSources
    with BootstrapLauncher with Bloop.Module {
  def crossScalaVersion     = crossValue
  def skipBloop             = !ScalaVersions.binaries.contains(crossScalaVersion)
  def crossFullScalaVersion = true
  def moduleDeps = Seq(
    shared.kernel(),
    scala.`scala-interpreter`()
  )
  def ivyDeps = Agg(
    Deps.caseApp,
    Deps.classPathUtil,
    Deps.scalafmtDynamic.withDottyCompat(crossScalaVersion)
  )
  object test extends CrossSbtModuleTests with AlmondTestModule {
    def moduleDeps = super.moduleDeps ++ Seq(
      scala.`scala-interpreter`().test
    )
  }

  def resolvedIvyDeps = T {
    // Ensure we stay on slf4j 1.x
    // Kind of unnecessary now that scala-kernel-api doesn't bring slf4j-api any more,
    // but keeping that just in case…
    val value = super.resolvedIvyDeps()
    val jarNames = value
      .map(_.path.last)
      .filter(_.endsWith(".jar"))
      .map(_.stripSuffix(".jar"))
    val slf4jJars = jarNames.filter(_.startsWith("slf4j-"))
    assert(slf4jJars.nonEmpty, "No slf4j JARs found")
    val wrongSlf4jVersionJars = slf4jJars.filter { name =>
      val version = name.split('-').dropWhile(_.head.isLetter).mkString("-")
      !version.startsWith("1.")
    }
    if (wrongSlf4jVersionJars.nonEmpty)
      sys.error(s"Found some slf4j non-1.x JARs: ${wrongSlf4jVersionJars.mkString(", ")}")

    value
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
  def launcherSharedClassPath = {
    val mod: AlmondModule with ExternalSources = scala.`scala-kernel-api`()
    mod.transitiveJars() ++
      mod.unmanagedClasspath() ++
      mod.resolvedRunIvyDeps() ++
      mod.transitiveSourceJars() ++
      mod.externalSources()
  }

  def manifest = T {
    import java.util.jar.Attributes.Name
    val ver = publishVersion()
    super.manifest().add(
      Name.IMPLEMENTATION_TITLE.toString   -> "scala-kernel",
      Name.IMPLEMENTATION_VERSION.toString -> ver,
      Name.SPECIFICATION_VENDOR.toString   -> "sh.almond",
      Name.SPECIFICATION_TITLE.toString    -> "scala-kernel",
      "Implementation-Vendor-Id"           -> "sh.almond",
      "Specification-Version"              -> ver,
      Name.IMPLEMENTATION_VENDOR.toString  -> "sh.almond"
    )
  }
  def mainClass = Some("almond.ScalaKernel")
}

trait CoursierLogger extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def moduleDeps = super.moduleDeps ++ Seq(
    shared.`interpreter-api`(),
    shared.logger()
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    Deps.collectionCompat,
    Deps.coursierApi,
    Deps.scalatags
  )
}

trait SharedDirectives extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def ivyDeps = super.ivyDeps() ++ Agg(
    Deps.directiveHandler,
    Deps.jsoniterScalaCore
  )
  def compileIvyDeps = Agg(
    Deps.jsoniterScalaMacros
  )
}

trait Launcher extends AlmondSimpleModule with BootstrapLauncher with PropertyFile
    with Bloop.Module {
  private def sv   = ScalaVersions.scala3Latest
  def scalaVersion = sv
  def moduleDeps = Seq(
    scala.`coursier-logger`(ScalaVersions.scala3Compat),
    scala.`shared-directives`(ScalaVersions.scala3Compat),
    shared.kernel(ScalaVersions.scala3Compat)
  )
  def ivyDeps = Agg(
    Deps.caseApp,
    Deps.coursierLauncher,
    Deps.fansi,
    Deps.scalaparse
  )

  def propertyFilePath = "almond/launcher/launcher.properties"
  def propertyExtra = T {
    val mainClass = scala.`scala-kernel`(ScalaVersions.scala3Latest).mainClass().getOrElse {
      sys.error("No main class found")
    }
    Seq(
      "kernel-main-class"        -> mainClass,
      "ammonite-version"         -> Versions.ammonite,
      "default-scala212-version" -> ScalaVersions.scala212,
      "default-scala213-version" -> ScalaVersions.scala213,
      "default-scala-version"    -> ScalaVersions.scala3Latest
    )
  }
}

trait AlmondScalaPy extends Cross.Module[String] with AlmondModule with Mima {
  def crossScalaVersion = crossValue
  def ivyDeps = Agg(
    Deps.jvmRepr
  )
  def compileIvyDeps = Agg(
    Deps.scalapy
  )
}

trait AlmondRx extends Cross.Module[String] with AlmondModule with Mima {
  def crossScalaVersion = crossValue
  def compileModuleDeps = Seq(
    scala.`scala-kernel-api`()
  )
  def ivyDeps = Agg(
    Deps.scalaRx
  )
}

trait Echo extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def moduleDeps = Seq(
    shared.kernel()
  )
  def ivyDeps = Agg(
    Deps.caseApp
  )
  def propertyFilePath = "almond/echo.properties"
  object test extends this.CrossSbtModuleTests with AlmondTestModule {
    def moduleDeps = super.moduleDeps ++ Seq(
      shared.test()
    )
  }
}

trait ToreeHooks extends Cross.Module[String] with AlmondModule {
  def crossScalaVersion = crossValue
  def compileModuleDeps = super.compileModuleDeps ++ Seq(
    scala.`scala-kernel-api`(ScalaVersions.binary(crossScalaVersion))
  )
}

object shared extends Module {
  object `logger-scala2-macros` extends Cross[LoggerScala2Macros](ScalaVersions.scala2Binaries)
  object logger                 extends Cross[Logger](ScalaVersions.binaries)
  object channels               extends Cross[Channels](ScalaVersions.binaries)
  object protocol               extends Cross[Protocol](ScalaVersions.binaries)
  object `interpreter-api`      extends Cross[InterpreterApi](ScalaVersions.binaries)
  object interpreter            extends Cross[Interpreter](ScalaVersions.binaries)
  object kernel                 extends Cross[Kernel](ScalaVersions.binaries)
  object test                   extends Cross[Test](ScalaVersions.binaries)
  object `test-kit`             extends Cross[TestKit](ScalaVersions.all)
}

// FIXME Can't use 'scala' because of macro hygiene issues in some mill macros
object scala extends Module {
  implicit def millModuleBasePath: define.Ctx.BasePath =
    define.Ctx.BasePath(super.millModuleBasePath.value / os.up / "scala")
  object `jupyter-api`       extends Cross[JupyterApi](ScalaVersions.binaries)
  object `scala-kernel-api`  extends Cross[ScalaKernelApi](ScalaVersions.all)
  object `scala-interpreter` extends Cross[ScalaInterpreter](ScalaVersions.all)
  object `scala-kernel`      extends Cross[ScalaKernel](ScalaVersions.all)
  object `coursier-logger`   extends Cross[CoursierLogger](ScalaVersions.binaries)
  object `shared-directives`
      extends Cross[SharedDirectives]("2.12.15" +: ScalaVersions.binaries)
  object launcher         extends Launcher
  object `almond-scalapy` extends Cross[AlmondScalaPy](ScalaVersions.binaries)
  object `almond-rx` extends Cross[AlmondRx](Seq(ScalaVersions.scala212, ScalaVersions.scala213))

  object `toree-hooks` extends Cross[ToreeHooks](ScalaVersions.binaries)

  object `test-definitions` extends Cross[TestDefinitions](ScalaVersions.all)
  object `local-repo`       extends Cross[KernelLocalRepo](ScalaVersions.all)
  object integration        extends Integration

  object examples extends Examples
}

trait Examples extends SbtModule {
  private def examplesScalaVersion = "2.12.19"
  private def baseRepoRoot         = os.sub / "out" / "repo"
  def scalaVersion                 = ScalaVersions.scala3Latest
  object test extends SbtModuleTests {
    def testFramework = "munit.Framework"
    def ivyDeps = T {
      super.ivyDeps() ++ Agg(
        Deps.expecty,
        Deps.munit,
        Deps.osLib,
        Deps.pprint,
        Deps.upickle
      )
    }
    def forkArgs = T {
      scala.`almond-scalapy`(ScalaVersions.scala212)
        .publishLocalNoFluff((baseRepoRoot / "{VERSION}").toString)()
      super.forkArgs() ++ Seq(
        s"-Dalmond.examples.dir=${os.pwd / "examples"}",
        s"-Dalmond.examples.output-dir=${T.dest / "output"}",
        s"-Dalmond.examples.jupyter-path=${T.dest / "jupyter"}",
        s"-Dalmond.examples.launcher=${scala.`scala-kernel`(examplesScalaVersion).launcher().path}",
        s"-Dalmond.examples.repo-root=${baseRepoRoot / scala.`scala-kernel`(examplesScalaVersion).publishVersion()}"
      )
    }
  }
}

trait TestKit extends Cross.Module[String] with CrossSbtModule with Bloop.Module {
  def crossScalaVersion = crossValue
  def skipBloop         = !ScalaVersions.binaries.contains(crossScalaVersion)
  def moduleDeps = Seq(
    shared.interpreter()
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    Deps.expecty,
    Deps.osLib,
    Deps.pprint,
    Deps.testUtil
  )
}

trait TestDefinitions extends Cross.Module[String] with AlmondUnpublishedModule with Bloop.Module {
  def crossScalaVersion = crossValue
  def skipBloop         = !ScalaVersions.binaries.contains(crossScalaVersion)

  def moduleDeps = super.moduleDeps ++ Seq(
    shared.`test-kit`()
  )
  def ivyDeps = T {
    Agg(
      Deps.coursierApi,
      Deps.upickle
    )
  }
}

trait KernelLocalRepo extends Cross.Module[String] with LocalRepo {
  def testScalaVersion = crossValue
  def stubsModules = {
    val scalaVersionSpecific =
      if (isScala3(testScalaVersion)) Seq.empty
      else Seq(shared.`logger-scala2-macros`(ScalaVersions.binary(testScalaVersion)))

    Seq(
      shared.kernel(ScalaVersions.binary(testScalaVersion)),
      shared.interpreter(ScalaVersions.binary(testScalaVersion)),
      shared.`interpreter-api`(ScalaVersions.binary(testScalaVersion)),
      shared.protocol(ScalaVersions.binary(testScalaVersion)),
      shared.channels(ScalaVersions.binary(testScalaVersion)),
      shared.logger(ScalaVersions.binary(testScalaVersion)),
      scala.`scala-kernel`(testScalaVersion),
      scala.`scala-kernel-api`(testScalaVersion),
      scala.`jupyter-api`(ScalaVersions.binary(testScalaVersion)),
      scala.`scala-interpreter`(testScalaVersion),
      scala.`toree-hooks`(ScalaVersions.binary(testScalaVersion)),
      scala.`coursier-logger`(ScalaVersions.binary(testScalaVersion)),
      scala.`shared-directives`(ScalaVersions.binary(testScalaVersion)),
      scala.launcher,
      shared.kernel(ScalaVersions.binary(ScalaVersions.scala3Latest)),
      shared.interpreter(ScalaVersions.binary(ScalaVersions.scala3Latest)),
      shared.`interpreter-api`(ScalaVersions.binary(ScalaVersions.scala3Latest)),
      shared.protocol(ScalaVersions.binary(ScalaVersions.scala3Latest)),
      shared.channels(ScalaVersions.binary(ScalaVersions.scala3Latest)),
      shared.logger(ScalaVersions.binary(ScalaVersions.scala3Latest))
    ) ++ scalaVersionSpecific
  }
  def version = scala.`scala-kernel`(testScalaVersion).publishVersion()
}

trait Integration extends SbtModule {
  private def scalaVersion0 = ScalaVersions.scala213
  def scalaVersion          = scalaVersion0

  def moduleDeps = super.moduleDeps ++ Seq(
    shared.`test-kit`(scalaVersion0),
    scala.`test-definitions`(scalaVersion0)
  )
  def ivyDeps = super.ivyDeps() ++ Agg(
    Deps.munit,
    Deps.osLib,
    Deps.pprint,
    Deps.testUtil
  )

  object test extends SbtModuleTests with TestCommand {
    object helper extends ScalaModule {
      def scalaVersion = ScalaVersions.scala3Latest
      def scala213 = T {
        runClasspath()
          .map(_.path)
          .map(_.last)
          .filter(_.startsWith("scala-library-2.13."))
          .map(_.stripPrefix("scala-library-"))
          .filter(_.endsWith(".jar"))
          .map(_.stripSuffix(".jar"))
          .filter(!_.contains("-"))
          .headOption
          .getOrElse {
            sys.error(s"Cannot get Scala 2.13 version pulled by Scala ${scalaVersion()}")
          }
      }
    }
    def testFramework = "munit.Framework"
    def forkArgs = T {
      scala.`local-repo`(ScalaVersions.scala212).localRepo()
      scala.`local-repo`(ScalaVersions.scala213).localRepo()
      scala.`local-repo`(ScalaVersions.scala3Latest).localRepo()
      val version = scala.`local-repo`(ScalaVersions.scala3Latest).version()
      super.forkArgs() ++ Seq(
        "-Xmx768m", // let's not use too much memory here, Windows CI sometimes runs short on it
        s"-Dalmond.test.local-repo=${scala.`local-repo`(ScalaVersions.scala3Latest).repoRoot.toString.replace("{VERSION}", version)}",
        s"-Dalmond.test.version=$version",
        s"-Dalmond.test.cs-launcher=${GetCs.cs(Deps.coursier.dep.version, "2.1.2")}",
        s"-Dalmond.test.scala-version=${ScalaVersions.scala3Latest}",
        s"-Dalmond.test.scala212-version=${ScalaVersions.scala212}",
        s"-Dalmond.test.scala213-version=${ScalaVersions.scala213}",
        s"-Dalmond.test.scala213-pulled-by-3-version=${helper.scala213()}"
      )
    }
    def tmpDirBase = T.persistent {
      PathRef(T.dest / "working-dir")
    }
    def forkEnv = super.forkEnv() ++ Seq(
      "ALMOND_INTEGRATION_TMP" -> tmpDirBase().path.toString
    )
  }
}

object echo extends Cross[Echo](ScalaVersions.binaries)

object docs extends ScalaModule with AlmondRepositories {
  private def scalaVersion0 = ScalaVersions.scala213
  def scalaVersion          = scalaVersion0
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
        case "--npm-install" :: rem    => processArgs(true, yarnRunBuild, watch, relativize, rem)
        case "--yarn-run-build" :: rem => processArgs(npmInstall, true, watch, relativize, rem)
        case "--watch" :: rem      => processArgs(npmInstall, yarnRunBuild, true, relativize, rem)
        case "--relativize" :: rem => processArgs(npmInstall, yarnRunBuild, watch, true, rem)
        case _                     => (npmInstall, yarnRunBuild, watch, relativize, args)
      }
    val (npmInstall, yarnRunBuild, watch, relativize, args0) =
      processArgs(false, false, false, false, args.toList)

    val ver           = scala.`scala-kernel-api`(scalaVersion0).publishVersion()
    val latestRelease = settings.latestTaggedVersion
    val ammVer        = Deps.ammoniteReplApi.dep.version
    val scalaVer      = scalaVersion0

    val isSnapshot = ver.endsWith("SNAPSHOT")
    val extraSbt =
      if (isSnapshot) """resolvers += Resolver.sonatypeRepo("snapshots")""" + "\n"
      else ""
    val extraCoursierArgs =
      if (isSnapshot) "-r sonatype:snapshots "
      else ""

    val outputDir = "docs/processed-pages"

    val allArgs = Seq(
      "--in",
      "docs/pages",
      "--out",
      outputDir,
      "--site.VERSION",
      ver,
      "--site.LATEST_RELEASE",
      latestRelease,
      "--site.EXTRA_SBT",
      extraSbt,
      "--site.AMMONITE_VERSION",
      ammVer,
      "--site.SCALA_VERSION",
      scalaVer,
      "--site.SCALA212_VERSION",
      ScalaVersions.scala212,
      "--site.SCALA213_VERSION",
      ScalaVersions.scala213,
      "--site.EXTRA_COURSIER_ARGS",
      extraCoursierArgs
    ) ++ (if (watch) Seq("--watch") else Nil) ++ args0

    // TODO Run yarn run thing right after, add --watch mode

    val websiteDir = os.pwd / "docs" / "website"

    if (npmInstall)
      Util.run(Seq("npm", "install"), dir = websiteDir.toIO)

    def runMdoc(): Unit =
      // adapted from https://github.com/com-lihaoyi/mill/blob/c500ca986ab79af3ce59ba65a093146672092307/scalalib/src/JavaModule.scala#L488-L494
      mill.util.Jvm.runSubprocess(
        finalMainClass(),
        runClasspath().map(_.path),
        Nil,
        forkEnv(),
        allArgs,
        workingDir = forkWorkingDir()
      )

    if (watch)
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
    else {
      runMdoc()
      if (yarnRunBuild)
        Util.run(Seq("yarn", "run", "build"), dir = websiteDir.toIO)
      if (relativize)
        Relativize.relativize((websiteDir / "build").toNIO)
    }
  }
}

object dev extends Module {

  def jupyter0(args: Seq[String], fast: Boolean, console: Boolean = false) = {
    val (sv, args0) = args match {
      case Seq(sv, rem @ _*) if sv.startsWith("2.") || sv.startsWith("3.") =>
        (sv, rem)
      case _ => (ScalaVersions.scala213, args)
    }
    val launcher =
      if (fast) scala.`scala-kernel`(sv).fastLauncher
      else scala.`scala-kernel`(sv).launcher
    val specialLauncher =
      if (fast) scala.launcher.fastLauncher
      else scala.launcher.launcher
    T.command {
      val jupyterDir       = T.ctx().dest / "jupyter"
      val launcher0        = launcher().path.toNIO
      val specialLauncher0 = specialLauncher().path.toNIO
      if (console)
        jupyterConsole0(launcher0, specialLauncher0, jupyterDir.toNIO, args0)
      else
        jupyterServer(launcher0, specialLauncher0, jupyterDir.toNIO, args0)
    }
  }

  def jupyter(args: String*) =
    jupyter0(args, fast = false)

  def jupyterFast(args: String*) =
    jupyter0(args, fast = true)

  def jupyterConsole(args: String*) =
    jupyter0(args, fast = false, console = true)

  def jupyterConsoleFast(args: String*) =
    jupyter0(args, fast = true, console = true)

  def scala212() = T.command {
    println(ScalaVersions.scala212)
  }
  def scala213() = T.command {
    println(ScalaVersions.scala213)
  }
  def scala3() = T.command {
    println(ScalaVersions.scala3Latest)
  }
  def scalaVersions() = T.command {
    for (sv <- ScalaVersions.all)
      println(sv)
  }

  def launcher(scalaVersion: String = ScalaVersions.scala213) = T.command {
    val launcher = scala.`scala-kernel`(scalaVersion).launcher().path.toNIO
    println(launcher)
  }

  def specialLauncher(scalaVersion: String = ScalaVersions.scala213) = T.command {
    val launcher = scala.launcher.launcher().path.toNIO
    println(launcher)
  }

  def launcherFast(scalaVersion: String = ScalaVersions.scala213) = T.command {
    val launcher = scala.`scala-kernel`(scalaVersion).fastLauncher().path.toNIO
    println(launcher)
  }

  def specialLauncherFast(scalaVersion: String = ScalaVersions.scala213) = T.command {
    val launcher = scala.launcher.fastLauncher().path.toNIO
    println(launcher)
  }
}

def ghOrg  = "almond-sh"
def ghName = "almond"
object ci extends Module {
  def uploadLaunchers(almondVersion: String = buildVersion) = T.command {
    def ghToken() = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val scalaVersions = Seq(
      ScalaVersions.scala212,
      ScalaVersions.scala213,
      ScalaVersions.scala3Latest
    )
    val launchers = scalaVersions.map { sv =>
      val sbv    = sv.split('.').take(2).mkString(".")
      val output = T.dest / s"launcher-$sv"
      os.proc(
        "cs",
        "bootstrap",
        "--no-default",
        "-r",
        "central",
        "-r",
        "jitpack",
        s"sh.almond:scala-kernel_$sv:$almondVersion",
        "--shared",
        s"sh.almond:scala-kernel-api_$sv",
        "-o",
        output
      ).call(stdin = os.Inherit, stdout = os.Inherit)

      (output, s"almond-scala-$sbv")
    }
    val (tag, overwriteAssets) =
      if (almondVersion.endsWith("-SNAPSHOT")) ("nightly", true)
      else ("v" + almondVersion, false)
    Upload.upload(ghOrg, ghName, ghToken(), tag, dryRun = false, overwrite = overwriteAssets)(
      launchers: _*
    )
  }

  def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) =
    T.command {
      val timeout     = 10.minutes
      val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
      val pgpPassword = sys.env("PGP_PASSWORD")
      val data        = T.sequence(tasks.value)()

      settings.publishSonatype(
        credentials = credentials,
        pgpPassword = pgpPassword,
        data = data,
        timeout = timeout,
        log = T.ctx().log
      )
    }
}

object dummy extends Module {
  // dummy module to get Scala Steward updates for ammonite-spark
  object `amm-spark` extends ScalaModule {
    def scalaVersion = ScalaVersions.scala212
    def ivyDeps = super.ivyDeps() ++ Agg(
      Deps.ammoniteSpark
    )
  }
}

object CrossSources {
  def extraSourcesDirs(sv: String, millSourcePath: os.Path): Seq[PathRef] = {
    val (maj, min) = sv.split('.') match {
      case Array(maj0, min0, _*) if min0.nonEmpty && min0.forall(_.isDigit) =>
        (maj0, min0.toInt)
      case _ =>
        sys.error(s"Malformed Scala version: $sv")
    }
    val baseDir = millSourcePath / "src" / "main"
    (0 to min).map(min0 => PathRef(baseDir / s"scala-$maj.$min0+"))
  }
}
