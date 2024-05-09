import $file.deps, deps.{Deps, ScalaVersions}
import $file.mima, mima.binaryCompatibilityVersions

import $ivy.`io.get-coursier::coursier-launcher:2.1.2`

import java.io.File
import java.nio.file.{Files, Path}
import java.util.{Arrays, Properties}

import mill._, scalalib.{CrossSbtModule => _, _}
import mill.scalalib.api.ZincWorkerUtil

import scala.annotation.tailrec
import scala.concurrent.duration._

lazy val latestTaggedVersion = os.proc("git", "describe", "--abbrev=0", "--tags", "--match", "v*")
  .call().out
  .trim()
lazy val buildVersion = {
  val gitHead = os.proc("git", "rev-parse", "HEAD").call().out.trim()
  val maybeExactTag = scala.util.Try {
    os.proc("git", "describe", "--exact-match", "--tags", "--always", gitHead)
      .call().out
      .trim()
      .stripPrefix("v")
  }
  maybeExactTag.toOption.getOrElse {
    val commitsSinceTaggedVersion =
      os.proc("git", "rev-list", gitHead, "--not", latestTaggedVersion, "--count")
        .call().out.trim()
        .toInt
    val gitHash = os.proc("git", "rev-parse", "--short", "HEAD").call().out.trim()
    s"${latestTaggedVersion.stripPrefix("v")}-$commitsSinceTaggedVersion-$gitHash-SNAPSHOT"
  }
}

// Adapted from https://github.com/lihaoyi/mill/blob/0.9.3/scalalib/src/MiscModule.scala/#L80-L100
// and https://github.com/com-lihaoyi/mill/blob/c75e29c78cfc3c1e04776978bfc8e5697f8ca1aa/scalalib/src/mill/scalalib/CrossSbtModule.scala#L7
// Compared to the original code, we ensure `scalaVersion()` rather than `crossScalaVersion` is
// used when computing paths, as the former is always a valid Scala version,
// while the latter can be a 3.x version while we compile using Scala 2.x
// (and later rely on dotty compatibility to mix Scala 2 / Scala 3 modules).
trait CrossSbtModule extends mill.scalalib.SbtModule with mill.scalalib.CrossModuleBase { outer =>

  def sources = T.sources {
    super.sources() ++ scalaVersionDirectoryNames.map(s =>
      PathRef(millSourcePath / "src" / "main" / s"scala-$s")
    )
  }
  trait CrossSbtModuleTests extends SbtModuleTests {
    override def millSourcePath = outer.millSourcePath
    def sources = T.sources {
      super.sources() ++ scalaVersionDirectoryNames.map(s =>
        PathRef(millSourcePath / "src" / "test" / s"scala-$s")
      )
    }
  }
  trait Tests extends CrossSbtModuleTests
}

trait AlmondRepositories extends CoursierModule {
  def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      coursier.Repositories.jitpack
    )
  }
}

trait AlmondPublishModule extends PublishModule {
  import mill.scalalib.publish._
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "sh.almond",
    url = "https://github.com/almond-sh/almond",
    licenses = Seq(License.`BSD-3-Clause`),
    versionControl = VersionControl.github("almond-sh", "almond"),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    )
  )
  def publishVersion = T(buildVersion)
}

trait ExternalSources extends CrossSbtModule {
  // def allIvyDeps = T((transitiveIvyDeps(): Agg[Dep]) ++ (scalaLibraryIvyDeps(): Agg[Dep]))
  def externalSources = T {
    resolveDeps(T.task(transitiveCompileIvyDeps() ++ transitiveIvyDeps()), sources = true)()
  }
}

trait TransitiveSources extends SbtModule {
  def transitiveJars: T[Seq[PathRef]] = T {
    Seq(jar()) ++ T.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveJars
      case mod                    => mod.jar.map(Seq(_))
    }().flatten
  }
  def transitiveSourceJars: T[Seq[PathRef]] = T {
    Seq(sourceJar()) ++ T.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveSourceJars
      case mod                    => mod.sourceJar.map(Seq(_))
    }().flatten
  }
  def transitiveSources: T[Seq[PathRef]] = T.sources {
    sources() ++ T.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveSources
      case mod                    => mod.sources
    }().flatten
  }
}

trait PublishLocalNoFluff extends PublishModule {

  def emptyZip = T {
    import java.io._
    import java.util.zip._
    val dest = T.dest / "empty.zip"
    val baos = new ByteArrayOutputStream
    val zos  = new ZipOutputStream(baos)
    zos.finish()
    zos.close()
    os.write(dest, baos.toByteArray)
    PathRef(dest)
  }
  // adapted from https://github.com/com-lihaoyi/mill/blob/fea79f0515dda1def83500f0f49993e93338c3de/scalalib/src/PublishModule.scala#L70-L85
  // writes empty zips as source and doc JARs
  def publishLocalNoFluff(localIvyRepo: String = null): define.Command[PathRef] = T.command {

    import mill.scalalib.publish.LocalIvyPublisher
    val publisher = localIvyRepo match {
      case null => LocalIvyPublisher
      case repo =>
        new LocalIvyPublisher(os.Path(repo.replace("{VERSION}", publishVersion()), os.pwd))
    }

    publisher.publishLocal(
      jar = jar().path,
      sourcesJar = sourceJar().path,
      docJar = emptyZip().path,
      pom = pom().path,
      ivy = ivy().path,
      artifact = artifactMetadata(),
      extras = extraPublish()
    )

    jar()
  }
}

trait AlmondArtifactName extends SbtModule {
  def artifactName =
    millModuleSegments
      .parts
      .dropWhile(_ == "scala")
      .dropWhile(_ == "shared")
      .take(1)
      .mkString("-")
}

trait AlmondScalacOptions extends ScalaModule {
  def scalacOptions = T {
    // see http://tpolecat.github.io/2017/04/25/scalac-flags.html
    val sv = scalaVersion()
    val scala2Options =
      if (sv.startsWith("2.")) Seq("-explaintypes")
      else Nil
    super.scalacOptions() ++ scala2Options ++ Seq(
      "-deprecation",
      "-feature",
      "-encoding",
      "utf-8",
      "-language:higherKinds",
      "-unchecked"
    )
  }
}

trait AlmondSimpleModule
    extends SbtModule
    with AlmondRepositories
    with AlmondPublishModule
    with TransitiveSources
    with AlmondArtifactName
    with PublishLocalNoFluff
    with AlmondScalacOptions

trait AlmondUnpublishedModule
    extends CrossSbtModule
    with AlmondRepositories
    with TransitiveSources
    with AlmondArtifactName
    with AlmondScalacOptions

trait AlmondModule
    extends CrossSbtModule
    with AlmondRepositories
    with AlmondPublishModule
    with TransitiveSources
    with AlmondArtifactName
    with PublishLocalNoFluff
    with AlmondScalacOptions {

  // from https://github.com/VirtusLab/scala-cli/blob/cf77234ab981332531cbcb0d6ae565de009ae252/build.sc#L501-L522
  // pin scala3-library suffix, so that 2.13 modules can have us as moduleDep fine
  def mandatoryIvyDeps = T {
    super.mandatoryIvyDeps().map { dep =>
      val isScala3Lib =
        dep.dep.module.organization.value == "org.scala-lang" &&
        dep.dep.module.name.value == "scala3-library" &&
        (dep.cross match {
          case _: CrossVersion.Binary => true
          case _                      => false
        })
      if (isScala3Lib)
        dep.copy(
          dep = dep.dep.withModule(
            dep.dep.module.withName(
              coursier.ModuleName(dep.dep.module.name.value + "_3")
            )
          ),
          cross = CrossVersion.empty(dep.cross.platformed)
        )
      else dep
    }
  }
  def transitiveIvyDeps = T {
    super.transitiveIvyDeps().map { dep =>
      val isScala3JarWithSuffix =
        dep.dep.module.organization.value == "org.scala-lang" &&
        dep.dep.module.name.value.startsWith("scala3-")
      if (isScala3JarWithSuffix)
        dep.copy(force = false)
      else dep
    }
  }
}

trait AlmondTestModule
    extends ScalaModule
    with TestModule
    with AlmondRepositories
    with AlmondScalacOptions {

  // originally based on https://github.com/com-lihaoyi/mill/blob/3335d2a2f7f33766a680e30df6a7d0dc0fbe08b3/scalalib/src/mill/scalalib/TestModule.scala#L80-L146
  // goal here is to use a coursier bootstrap rather than a manifest JAR when testUseArgsFile is true
  protected def testTask(
    args: Task[Seq[String]],
    globSelectors: Task[Seq[String]]
  ): Task[(String, Seq[mill.testrunner.TestResult])] =
    T.task {
      val outputPath  = T.dest / "out.json"
      val useArgsFile = testUseArgsFile()

      val (jvmArgs, props: Map[String, String]) =
        if (useArgsFile) {
          val (props, jvmArgs) = forkArgs().partition(_.startsWith("-D"))
          val sysProps =
            props
              .map(_.drop(2).split("[=]", 2))
              .map {
                case Array(k, v) => k -> v
                case Array(k)    => k -> ""
              }
              .toMap

          jvmArgs -> sysProps
        }
        else
          forkArgs() -> Map()

      val testArgs = mill.testrunner.TestArgs(
        framework = testFramework(),
        classpath = runClasspath().map(_.path),
        arguments = args(),
        sysProps = props,
        outputPath = outputPath,
        colored = T.log.colored,
        testCp = compile().classes.path,
        home = T.home,
        globSelectors = globSelectors()
      )

      val testRunnerClasspathArg = zincWorker().scalalibClasspath()
        .map(_.path.toNIO.toUri.toURL)
        .mkString(",")

      val argsFile = T.dest / "testargs"
      os.write(argsFile, upickle.default.write(testArgs))
      val mainArgs = Seq(testRunnerClasspathArg, argsFile.toString)

      runSubprocess(
        mainClass = "mill.testrunner.entrypoint.TestRunnerMain",
        classPath = (runClasspath() ++ zincWorker().testrunnerEntrypointClasspath()).map(_.path),
        jvmArgs = jvmArgs,
        envArgs = forkEnv(),
        mainArgs = mainArgs,
        workingDir = forkWorkingDir(),
        useCpPassingJar = useArgsFile
      )

      if (!os.exists(outputPath)) mill.api.Result.Failure("Test execution failed.")
      else
        try {
          val jsonOutput = ujson.read(outputPath.toIO)
          val (doneMsg, results) =
            upickle.default.read[(String, Seq[mill.testrunner.TestResult])](jsonOutput)
          TestModule.handleResults(doneMsg, results, Some(T.ctx()))
        }
        catch {
          case e: Throwable =>
            mill.api.Result.Failure("Test reporting failed: " + e)
        }
    }

  private def createBootstrapJar(
    dest: os.Path,
    classPath: Agg[os.Path],
    mainClass: String
  ): Unit = {
    import coursier.launcher._
    val content = Seq(ClassLoaderContent.fromUrls(classPath.toSeq.map(_.toNIO.toUri.toASCIIString)))
    val params  = Parameters.Bootstrap(content, mainClass)
    BootstrapGenerator.generate(params, dest.toNIO)
  }

  // originally based on https://github.com/com-lihaoyi/mill/blob/3335d2a2f7f33766a680e30df6a7d0dc0fbe08b3/main/util/src/mill/util/Jvm.scala#L117-L154
  // goal is to use coursier bootstraps rather than manifest JARs
  // the former load JARs via standard URLClassLoader-s, which is better dealt with in Ammonite and almond
  private def runSubprocess(
    mainClass: String,
    classPath: Agg[os.Path],
    jvmArgs: Seq[String] = Seq.empty,
    envArgs: Map[String, String] = Map.empty,
    mainArgs: Seq[String] = Seq.empty,
    workingDir: os.Path = null,
    useCpPassingJar: Boolean = false
  )(implicit ctx: mill.api.Ctx): Unit = {

    val (cp, mainClass0) =
      if (useCpPassingJar && !classPath.iterator.isEmpty) {
        val passingJar = os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)
        ctx.log.debug(
          s"Creating classpath passing jar '$passingJar' with Class-Path: ${classPath.iterator.map(_.toNIO.toUri.toURL.toExternalForm).mkString(" ")}"
        )
        createBootstrapJar(passingJar, classPath, mainClass)
        (Agg(passingJar), "coursier.bootstrap.launcher.Launcher")
      }
      else
        (classPath, mainClass)

    val args =
      Vector(mill.util.Jvm.javaExe) ++
        jvmArgs ++
        Vector("-cp", cp.iterator.mkString(java.io.File.pathSeparator), mainClass0) ++
        mainArgs

    ctx.log.debug(s"Run subprocess with args: ${args.map(a => s"'$a'").mkString(" ")}")

    mill.util.Jvm.runSubprocess(args, envArgs, workingDir)
  }

  def ivyDeps       = Agg(Deps.utest)
  def testFramework = "utest.runner.Framework"

  // from https://github.com/VirtusLab/scala-cli/blob/cf77234ab981332531cbcb0d6ae565de009ae252/build.sc#L501-L522
  // pin scala3-library suffix, so that 2.13 modules can have us as moduleDep fine
  def mandatoryIvyDeps = T {
    super.mandatoryIvyDeps().map { dep =>
      val isScala3Lib =
        dep.dep.module.organization.value == "org.scala-lang" &&
        dep.dep.module.name.value == "scala3-library" &&
        (dep.cross match {
          case _: CrossVersion.Binary => true
          case _                      => false
        })
      if (isScala3Lib)
        dep.copy(
          dep = dep.dep.withModule(
            dep.dep.module.withName(
              coursier.ModuleName(dep.dep.module.name.value + "_3")
            )
          ),
          cross = CrossVersion.empty(dep.cross.platformed)
        )
      else dep
    }
  }
  def transitiveIvyDeps = T {
    super.transitiveIvyDeps().map { dep =>
      val isScala3JarWithSuffix =
        dep.dep.module.organization.value == "org.scala-lang" &&
        dep.dep.module.name.value.startsWith("scala3-")
      if (isScala3JarWithSuffix)
        dep.copy(force = false)
      else dep
    }
  }
}

trait BootstrapLauncher extends SbtModule {

  def launcherClassPath       = T(runClasspath())
  def launcherSharedClassPath = T(Seq.empty[PathRef])

  import coursier.launcher._

  private def toEntry(jar: Path, resourceIfNotFromCache: Boolean = true): ClassPathEntry = {
    def default =
      if (resourceIfNotFromCache) toResourceEntry(jar)
      else ClassPathEntry.Url(jar.toUri.toASCIIString)
    val cacheRoot = coursier.cache.CacheDefaults.location.toPath
    if (jar.startsWith(cacheRoot)) {
      val rel = cacheRoot.relativize(jar).toString
        .replace("\\", "/") // Windows?
      if (rel.startsWith("https/"))
        ClassPathEntry.Url("https://" + rel.stripPrefix("https/"))
      else if (rel.startsWith("http/"))
        ClassPathEntry.Url("http://" + rel.stripPrefix("http/"))
      else
        default
    }
    else default
  }
  private def toResourceEntry(jar: Path): ClassPathEntry.Resource = {
    val lastModified = Files.getLastModifiedTime(jar)
    val content      = Files.readAllBytes(jar)
    ClassPathEntry.Resource(jar.getFileName.toString, lastModified.toMillis, content)
  }
  private def createLauncher(
    sharedCp: Seq[Path],
    cp: Seq[Path],
    mainClass: String,
    dest: Path,
    windows: Boolean,
    fast: Boolean // !fast means standalone (can be copied to another machine, …)
  ): Unit = {
    val sharedClassLoaderContent =
      if (fast)
        ClassLoaderContent(sharedCp.distinct.map(toEntry(_, resourceIfNotFromCache = false)))
      else ClassLoaderContent(sharedCp.distinct.map(toResourceEntry))
    val classLoaderContent =
      if (fast) ClassLoaderContent(cp.distinct.map(toEntry(_, resourceIfNotFromCache = false)))
      else ClassLoaderContent(cp.distinct.map(toEntry(_)))
    val preamble =
      if (windows) Preamble().withKind(Preamble.Kind.Bat)
      else Preamble()
    val params = Parameters.Bootstrap(Seq(sharedClassLoaderContent, classLoaderContent), mainClass)
      .withPreamble(preamble)
      .withHybridAssembly(true)

    Util.withLoader(BootstrapGenerator.getClass.getClassLoader) {
      BootstrapGenerator.generate(params, dest)
    }
  }

  private def isWindows: Boolean =
    System.getProperty("os.name")
      .toLowerCase(java.util.Locale.ROOT)
      .contains("windows")
  def unixLauncher = T {
    val mainClass = finalMainClass()
    val sharedCp  = launcherSharedClassPath().map(_.path.toNIO)
    val cp        = launcherClassPath().map(_.path.toNIO)
    val dest      = T.ctx().dest / "launcher"

    createLauncher(
      sharedCp,
      cp.filterNot(sharedCp.toSet),
      mainClass,
      dest.toNIO,
      windows = false,
      fast = false
    )

    PathRef(dest)
  }
  def windowsLauncher = T {
    val mainClass = finalMainClass()
    val sharedCp  = launcherSharedClassPath().map(_.path.toNIO)
    val cp        = launcherClassPath().map(_.path.toNIO)
    val dest      = T.ctx().dest / "launcher.bat"

    createLauncher(
      sharedCp,
      cp.filterNot(sharedCp.toSet),
      mainClass,
      dest.toNIO,
      windows = true,
      fast = false
    )

    PathRef(dest)
  }
  def launcher =
    if (isWindows) windowsLauncher
    else unixLauncher

  def unixFastLauncher = T {
    val mainClass = finalMainClass()
    val sharedCp  = launcherSharedClassPath().map(_.path.toNIO)
    val cp        = launcherClassPath().map(_.path.toNIO)
    val dest      = T.ctx().dest / "launcher"

    createLauncher(
      sharedCp,
      cp.filterNot(sharedCp.toSet),
      mainClass,
      dest.toNIO,
      windows = false,
      fast = true
    )

    PathRef(dest)
  }
  def windowsFastLauncher = T {
    val mainClass = finalMainClass()
    val sharedCp  = launcherSharedClassPath().map(_.path.toNIO)
    val cp        = launcherClassPath().map(_.path.toNIO)
    val dest      = T.ctx().dest / "launcher.bat"

    createLauncher(
      sharedCp,
      cp.filterNot(sharedCp.toSet),
      mainClass,
      dest.toNIO,
      windows = true,
      fast = true
    )

    PathRef(dest)
  }
  def fastLauncher =
    if (isWindows) windowsFastLauncher
    else unixFastLauncher
}

trait PropertyFile extends AlmondPublishModule {

  def propertyFilePath: String
  def propertyExtra: T[Seq[(String, String)]] = T(Seq.empty[(String, String)])

  def props = T {
    import sys.process._

    val dir = T.dest / "property-resources"
    val ver = publishVersion()

    // FIXME Only set if ammonite-spark is available for the current scala version?
    val ammSparkVer = Deps.ammoniteSpark.dep.version

    val f = dir / propertyFilePath.split('/').toSeq

    s"""commit-hash=${Seq("git", "rev-parse", "HEAD").!!.trim}
       |version=$ver
       |ammonite-spark-version=$ammSparkVer
       |""".stripMargin +
      propertyExtra()
        .map {
          case (k, v) =>
            s"""$k=$v
               |""".stripMargin
        }
        .mkString
  }
  def propResourcesDir = T {
    val dir = T.dest / "property-resources"
    val f   = dir / propertyFilePath.split('/').toSeq

    val content = props().getBytes("UTF-8")

    os.write.over(f, content, createFolders = true)
    System.err.println(s"Wrote $f")

    PathRef(dir)
  }
  def resources = T.sources {
    super.resources() ++ Seq(propResourcesDir())
  }
}

trait DependencyListResource extends CrossSbtModule {
  def userDependencies = T {
    val (_, res) = Lib.resolveDependenciesMetadata(
      repositoriesTask(),
      transitiveIvyDeps(),
      Some(mapDependencies()),
      customizer = resolutionCustomizer(),
      coursierCacheCustomizer = coursierCacheCustomizer()
    )
    res
      .orderedDependencies
      .map { dep =>
        (dep.module.organization.value, dep.module.name.value, dep.version)
      }
      .distinct
      .sorted
      .map {
        case (org, name, ver) =>
          s"$org:$name:$ver"
      }
      .mkString("\n")
  }
  def depResourcesDir = T {
    val content = userDependencies().getBytes("UTF-8")

    val dir = T.dest / "dependency-resources"
    val f   = dir / "almond" / "almond-user-dependencies.txt"

    os.write.over(f, content, createFolders = true)
    System.err.println(s"Wrote $f")

    PathRef(dir)
  }
  def resources = T.sources {
    super.resources() ++ Seq(depResourcesDir())
  }
}

object Util {
  def withLoader[T](loader: ClassLoader)(f: => T): T = {
    val thread = Thread.currentThread()
    val cl     = thread.getContextClassLoader
    try {
      thread.setContextClassLoader(loader)
      f
    }
    finally thread.setContextClassLoader(cl)
  }

  def run(cmd: Seq[String], dir: File = null) = {
    val b = new ProcessBuilder(cmd: _*)
    b.inheritIO()
    for (d <- Option(dir))
      b.directory(d)
    System.err.println(s"Running ${cmd.mkString(" ")}")
    val p       = b.start()
    val retCode = p.waitFor()
    if (retCode != 0)
      sys.error(s"Error running ${cmd.mkString(" ")} (return code: $retCode)")
  }

  def withBgProcess[T](
    cmd: Seq[String],
    dir: File = new File("."),
    waitFor: () => Unit = null
  )(f: => T): T = {

    val b = new ProcessBuilder(cmd: _*)
    b.inheritIO()
    b.directory(dir)
    var p: Process = null

    Option(waitFor) match {
      case Some(w) =>
        val t = new Thread("wait-for-condition") {
          setDaemon(true)
          override def run() = {
            w()
            System.err.println(s"Running ${cmd.mkString(" ")}")
            p = b.start()
          }
        }
        t.start()
      case None =>
        System.err.println(s"Running ${cmd.mkString(" ")}")
        p = b.start()
    }

    try f
    finally {
      p.destroy()
      p.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS)
      p.destroyForcibly()
    }
  }

  def waitForDir(dir: File): Unit = {
    @tailrec
    def helper(): Unit = {
      val found =
        dir.exists() && {
          assert(dir.isDirectory)
          dir.listFiles().nonEmpty
        }

      if (!found) {
        Thread.sleep(200L)
        helper()
      }
    }

    helper()
  }
}

def publishSonatype(
  credentials: String,
  pgpPassword: String,
  data: Seq[PublishModule.PublishData],
  timeout: Duration,
  log: mill.api.Logger
): Unit = {

  val artifacts = data.map {
    case PublishModule.PublishData(a, s) =>
      (s.map { case (p, f) => (p.path, f) }, a)
  }

  val isRelease = {
    val versions = artifacts.map(_._2.version).toSet
    val set      = versions.map(!_.endsWith("-SNAPSHOT"))
    assert(
      set.size == 1,
      s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}"
    )
    set.head
  }
  val publisher = new publish.SonatypePublisher(
    uri = "https://oss.sonatype.org/service/local",
    snapshotUri = "https://oss.sonatype.org/content/repositories/snapshots",
    credentials = credentials,
    signed = isRelease,
    gpgArgs = Seq(
      "--passphrase",
      pgpPassword,
      "--no-tty",
      "--pinentry-mode",
      "loopback",
      "--batch",
      "--yes",
      "-a",
      "-b"
    ),
    readTimeout = timeout.toMillis.toInt,
    connectTimeout = timeout.toMillis.toInt,
    log = log,
    workspace = os.pwd,
    env = sys.env,
    awaitTimeout = timeout.toMillis.toInt,
    stagingRelease = isRelease
  )

  publisher.publishAll(isRelease, artifacts: _*)
}

trait Mima extends com.github.lolgab.mill.mima.Mima {
  def mimaPreviousVersions = binaryCompatibilityVersions

  // same as https://github.com/lolgab/mill-mima/blob/de28f3e9fbe92867f98e35f8dfd3c3a777cc033d/mill-mima/src/com/github/lolgab/mill/mima/Mima.scala#L29-L44
  // except we're ok if mimaPreviousVersions is empty
  def mimaPreviousArtifacts = T {
    val versions = mimaPreviousVersions().distinct
    mill.api.Result.Success(
      Agg.from(
        versions.map(version =>
          ivy"${pomSettings().organization}:${artifactId()}:$version"
        )
      )
    )
  }
}

trait LocalRepo extends Module {

  def stubsModules: Seq[PublishLocalNoFluff]
  def version: T[String]

  def repoRoot = os.rel / "out" / "repo" / "{VERSION}"

  def localRepo = T {
    val tasks = stubsModules.map(_.publishLocalNoFluff(repoRoot.toString))
    define.Target.sequence(tasks)
  }
}

trait TestCommand extends TestModule {

  // based on https://github.com/com-lihaoyi/mill/blob/cfbafb806351c3e1664de4e2001d3d1ddda045da/scalalib/src/TestModule.scala#L98-L164
  def testCommand(args: String*) =
    T.command {
      import mill.testrunner.TestRunner

      val globSelectors = Nil
      val outputPath    = os.pwd / "test-output.json"
      val useArgsFile   = testUseArgsFile()

      val (jvmArgs, props: Map[String, String]) =
        if (useArgsFile) {
          val (props, jvmArgs) = forkArgs().partition(_.startsWith("-D"))
          val sysProps =
            props
              .map(_.drop(2).split("[=]", 2))
              .map {
                case Array(k, v) => k -> v
                case Array(k)    => k -> ""
              }
              .toMap

          jvmArgs -> sysProps
        }
        else
          forkArgs() -> Map()

      val testArgs = mill.testrunner.TestArgs(
        framework = testFramework(),
        classpath = runClasspath().map(_.path),
        arguments = args,
        sysProps = props,
        outputPath = outputPath,
        colored = T.log.colored,
        testCp = compile().classes.path,
        home = T.home,
        globSelectors = globSelectors
      )

      val testRunnerClasspathArg = zincWorker().scalalibClasspath()
        .map(_.path.toNIO.toUri.toURL)
        .mkString(",")

      val argsFile = T.dest / "testargs"
      os.write(argsFile, upickle.default.write(testArgs))
      val mainArgs   = Seq(testRunnerClasspathArg, argsFile.toString)
      val envArgs    = forkEnv()
      val workingDir = forkWorkingDir()

      val args0 = jvmSubprocessCommand(
        mainClass = "mill.testrunner.entrypoint.TestRunnerMain",
        classPath = (runClasspath() ++ zincWorker().testrunnerEntrypointClasspath()).map(_.path),
        jvmArgs = jvmArgs,
        envArgs = envArgs,
        mainArgs = mainArgs,
        workingDir = workingDir,
        useCpPassingJar = useArgsFile
      )

      val env = envArgs.toVector.map { case (k, v) => s"$k=$v" }.toSet
        .--(sys.env.toVector.map { case (k, v) => s"$k=$v" })
        .toVector
        .sorted

      Map(
        "args" -> args0,
        "cwd"  -> Seq(workingDir.toString),
        "env"  -> env
      )
    }

  def jvmSubprocessCommand(
    mainClass: String,
    classPath: Agg[os.Path],
    jvmArgs: Seq[String] = Seq.empty,
    envArgs: Map[String, String] = Map.empty,
    mainArgs: Seq[String] = Seq.empty,
    workingDir: os.Path = null,
    background: Boolean = false,
    useCpPassingJar: Boolean = false
  )(implicit ctx: mill.api.Ctx): Seq[String] = {

    import mill.util.Jvm

    val cp =
      if (useCpPassingJar && !classPath.iterator.isEmpty) {
        val passingJar = os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)
        ctx.log.debug(
          s"Creating classpath passing jar '$passingJar' with Class-Path: ${classPath.iterator.map(
              _.toNIO.toUri().toURL().toExternalForm()
            ).mkString(" ")}"
        )
        Jvm.createClasspathPassingJar(passingJar, classPath)
        Agg(passingJar)
      }
      else
        classPath

    Vector(Jvm.javaExe) ++
      jvmArgs ++
      Vector("-cp", cp.iterator.mkString(java.io.File.pathSeparator), mainClass) ++
      mainArgs
  }
}
