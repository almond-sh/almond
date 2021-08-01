import $file.deps, deps.Deps

import $ivy.`io.get-coursier::coursier-launcher:2.0.12`

import java.io.File
import java.nio.file.{Files, Path}
import java.util.Properties

import mill._, scalalib._

import scala.annotation.tailrec
import scala.concurrent.duration._

lazy val latestTaggedVersion = os.proc("git", "describe", "--abbrev=0", "--tags", "--match", "v*")
  .call().out
  .trim
lazy val buildVersion = {
  val gitHead = os.proc("git", "rev-parse", "HEAD").call().out.trim
  val maybeExactTag = scala.util.Try {
    os.proc("git", "describe", "--exact-match", "--tags", "--always", gitHead)
      .call().out
      .trim
      .stripPrefix("v")
  }
  maybeExactTag.toOption.getOrElse {
    val commitsSinceTaggedVersion =
      os.proc('git, "rev-list", gitHead, "--not", latestTaggedVersion, "--count")
        .call().out.trim
        .toInt
    val gitHash = os.proc("git", "rev-parse", "--short", "HEAD").call().out.trim
    s"${latestTaggedVersion.stripPrefix("v")}-$commitsSinceTaggedVersion-$gitHash-SNAPSHOT"
  }
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
      Developer("alexarchambault", "Alex Archambault","https://github.com/alexarchambault")
    )
  )
  def publishVersion = T{ buildVersion }
}

trait HasTests extends CrossSbtModule {
  trait Tests extends super.Tests {
    def ivyDeps = Agg(Deps.utest)
    def testFrameworks = Seq("utest.runner.Framework")
  }
}

trait ExternalSources extends CrossSbtModule {
  def allIvyDeps = T{ transitiveIvyDeps() ++ scalaLibraryIvyDeps() }
  def externalSources = T{
    resolveDeps(allIvyDeps, sources = true)()
  }
}

trait TransitiveSources extends CrossSbtModule {
  def transitiveJars: T[Seq[PathRef]] = T{
    Seq(jar()) ++ T.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveJars
      case mod => mod.jar.map(Seq(_))
    }().flatten
  }
  def transitiveSourceJars: T[Seq[PathRef]] = T{
    Seq(sourceJar()) ++ T.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveSourceJars
      case mod => mod.sourceJar.map(Seq(_))
    }().flatten
  }
  def transitiveSources: define.Sources = T.sources{
    sources() ++ T.traverse(moduleDeps) {
      case mod: TransitiveSources => mod.transitiveSources
      case mod => mod.sources
    }().flatten
  }
}

trait AlmondModule
  extends CrossSbtModule
  with AlmondRepositories
  with AlmondPublishModule
  with TransitiveSources {

  def scalacOptions = Seq(
    // see http://tpolecat.github.io/2017/04/25/scalac-flags.html
    "-deprecation",
    "-feature",
    "-explaintypes",
    "-encoding", "utf-8",
    "-language:higherKinds",
    "-unchecked"
  )

  def artifactName =
    millModuleSegments
      .parts
      .dropWhile(_ == "scala0")
      .dropWhile(_ == "shared")
      .take(1)
      .mkString("-")
}

trait BootstrapLauncher extends CrossSbtModule {

  def launcherClassPath = T{ runClasspath() }
  def launcherSharedClassPath = T{ Seq.empty[PathRef] }

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
    } else default
  }
  private def toResourceEntry(jar: Path): ClassPathEntry.Resource = {
    val lastModified = Files.getLastModifiedTime(jar)
    val content = Files.readAllBytes(jar)
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
      if (fast) ClassLoaderContent(sharedCp.distinct.map(toEntry(_, resourceIfNotFromCache = false)))
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
  def unixLauncher = T{
    val mainClass = finalMainClass()
    val sharedCp = launcherSharedClassPath().map(_.path.toNIO)
    val cp = launcherClassPath().map(_.path.toNIO)
    val dest = T.ctx().dest / "launcher"

    createLauncher(sharedCp, cp.filterNot(sharedCp.toSet), mainClass, dest.toNIO, windows = false, fast = false)

    PathRef(dest)
  }
  def windowsLauncher = T{
    val mainClass = finalMainClass()
    val sharedCp = launcherSharedClassPath().map(_.path.toNIO)
    val cp = launcherClassPath().map(_.path.toNIO)
    val dest = T.ctx().dest / "launcher.bat"

    createLauncher(sharedCp, cp.filterNot(sharedCp.toSet), mainClass, dest.toNIO, windows = true, fast = false)

    PathRef(dest)
  }
  def launcher =
    if (isWindows) windowsLauncher
    else unixLauncher

  def unixFastLauncher = T{
    val mainClass = finalMainClass()
    val sharedCp = launcherSharedClassPath().map(_.path.toNIO)
    val cp = launcherClassPath().map(_.path.toNIO)
    val dest = T.ctx().dest / "launcher"

    createLauncher(sharedCp, cp.filterNot(sharedCp.toSet), mainClass, dest.toNIO, windows = false, fast = true)

    PathRef(dest)
  }
  def windowsFastLauncher = T{
    val mainClass = finalMainClass()
    val sharedCp = launcherSharedClassPath().map(_.path.toNIO)
    val cp = launcherClassPath().map(_.path.toNIO)
    val dest = T.ctx().dest / "launcher.bat"

    createLauncher(sharedCp, cp.filterNot(sharedCp.toSet), mainClass, dest.toNIO, windows = true, fast = true)

    PathRef(dest)
  }
  def fastLauncher =
    if (isWindows) windowsFastLauncher
    else unixFastLauncher
}

trait PropertyFile extends AlmondPublishModule {

  def propertyFilePath: String
  def propertyExtra: Seq[(String, String)] = Nil

  def resources = T.sources{
    import sys.process._

    val dir = T.ctx().dest / "property-resources"
    val ver = publishVersion()
    val ammSparkVer = Deps.ammoniteSpark.dep.version

    val f = propertyFilePath.split('/').foldLeft(dir)(_ / _)
    os.write(f, Array.emptyByteArray, createFolders = true)

    val p = new Properties

    p.setProperty("version", ver)
    p.setProperty("commit-hash", Seq("git", "rev-parse", "HEAD").!!.trim)
    // FIXME Only set if ammonite-spark is available for the current scala version?
    p.setProperty("ammonite-spark-version", ammSparkVer)

    for ((k, v) <- propertyExtra)
      p.setProperty(k, v)

    val w = new java.io.FileOutputStream(f.toIO)
    p.store(w, "Almond properties")
    w.close()

    System.err.println(s"Wrote $f")

    super.resources() ++ Seq(PathRef(dir))
  }
}

trait DependencyListResource extends CrossSbtModule {
  def resources = T.sources {
    val (_, res) = Lib.resolveDependenciesMetadata(
      repositoriesTask(),
      resolveCoursierDependency().apply(_),
      transitiveIvyDeps(),
      Some(mapDependencies())
    )
    val content = res
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

    val dir = T.ctx().dest / "dependency-resources"
    val f = dir / "almond" / "almond-user-dependencies.txt"
    os.write(f, content.getBytes("UTF-8"), createFolders = true)

    System.err.println(s"Wrote $f")

    super.resources() ++ Seq(PathRef(dir))
  }
}

object Util {
  def withLoader[T](loader: ClassLoader)(f: => T): T = {
    val thread = Thread.currentThread()
    val cl = thread.getContextClassLoader
    try {
      thread.setContextClassLoader(loader)
      f
    } finally thread.setContextClassLoader(cl)
  }

  def run(cmd: Seq[String], dir: File = null) = {
    val b = new ProcessBuilder(cmd: _*)
    b.inheritIO()
    for (d <- Option(dir))
      b.directory(d)
    System.err.println(s"Running ${cmd.mkString(" ")}")
    val p = b.start()
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
    val set = versions.map(!_.endsWith("-SNAPSHOT"))
    assert(set.size == 1, s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}")
    set.head
  }
  val publisher = new publish.SonatypePublisher(
               uri = "https://oss.sonatype.org/service/local",
       snapshotUri = "https://oss.sonatype.org/content/repositories/snapshots",
       credentials = credentials,
            signed = isRelease,
           gpgArgs = Seq("--passphrase", pgpPassword, "--no-tty", "--pinentry-mode", "loopback", "--batch", "--yes", "-a", "-b"),
       readTimeout = timeout.toMillis.toInt,
    connectTimeout = timeout.toMillis.toInt,
               log = log,
      awaitTimeout = timeout.toMillis.toInt,
    stagingRelease = isRelease
  )

  publisher.publishAll(isRelease, artifacts: _*)
}
