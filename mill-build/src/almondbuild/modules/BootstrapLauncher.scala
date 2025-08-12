package almondbuild.modules

import mill._
import mill.api.{PathRef => _, _}
import mill.scalalib._

trait BootstrapLauncher extends SbtModule {

  def launcherClassPath       = Task(runClasspath())
  def launcherSharedClassPath = Task(Seq.empty[PathRef])

  import coursier.launcher._

  private def toEntry(jar: os.Path, resourceIfNotFromCache: Boolean = true): ClassPathEntry = {
    def default =
      if (resourceIfNotFromCache) toResourceEntry(jar)
      else ClassPathEntry.Url(jar.toNIO.toUri.toASCIIString)
    val cacheRoot = os.Path(coursier.cache.CacheDefaults.location)
    if (jar.startsWith(cacheRoot)) {
      val rel = jar.relativeTo(cacheRoot).asSubPath.toString
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
  private def toResourceEntry(jar: os.Path): ClassPathEntry =
    if (os.isDir(jar)) ClassPathEntry.Url(jar.toNIO.toUri.toASCIIString)
    else {
      val lastModified = os.mtime(jar)
      val content      = os.read.bytes(jar)
      ClassPathEntry.Resource(jar.last, lastModified, content)
    }
  private def createLauncher(
    sharedCp: Seq[os.Path],
    cp: Seq[os.Path],
    mainClass: String,
    dest: os.Path,
    windows: Boolean,
    fast: Boolean // !fast means standalone (can be copied to another machine, …)
  ): Unit = {
    val sharedCp0 = sharedCp.filter(os.exists(_))
    val cp0       = cp.filter(os.exists(_))
    val sharedClassLoaderContent =
      if (fast)
        ClassLoaderContent(sharedCp0.distinct.map(toEntry(_, resourceIfNotFromCache = false)))
      else ClassLoaderContent(sharedCp0.distinct.filter(os.exists(_)).map(toResourceEntry))
    val classLoaderContent =
      if (fast) ClassLoaderContent(cp0.distinct.map(toEntry(_, resourceIfNotFromCache = false)))
      else ClassLoaderContent(cp0.distinct.map(toEntry(_)))
    val preamble =
      if (windows) Preamble().withKind(Preamble.Kind.Bat)
      else Preamble()
    val params = Parameters.Bootstrap(Seq(sharedClassLoaderContent, classLoaderContent), mainClass)
      .withPreamble(preamble)
      .withHybridAssembly(true)

    Util.withLoader(BootstrapGenerator.getClass.getClassLoader) {
      BootstrapGenerator.generate(params, dest.toNIO)
    }
  }

  private def isWindows: Boolean =
    System.getProperty("os.name")
      .toLowerCase(java.util.Locale.ROOT)
      .contains("windows")
  def unixLauncher = Task {
    val mainClass = finalMainClass()
    val sharedCp  = launcherSharedClassPath().map(_.path)
    val cp        = launcherClassPath().map(_.path)
    val dest      = Task.dest / "launcher"

    createLauncher(
      sharedCp,
      cp.filterNot(sharedCp.toSet),
      mainClass,
      dest,
      windows = false,
      fast = false
    )

    PathRef(dest)
  }
  def windowsLauncher = Task {
    val mainClass = finalMainClass()
    val sharedCp  = launcherSharedClassPath().map(_.path)
    val cp        = launcherClassPath().map(_.path)
    val dest      = Task.dest / "launcher.bat"

    createLauncher(
      sharedCp,
      cp.filterNot(sharedCp.toSet),
      mainClass,
      dest,
      windows = true,
      fast = false
    )

    PathRef(dest)
  }
  def launcher =
    if (isWindows) windowsLauncher
    else unixLauncher

  def unixFastLauncher = Task {
    val mainClass = finalMainClass()
    val sharedCp  = launcherSharedClassPath().map(_.path)
    val cp        = launcherClassPath().map(_.path)
    val dest      = Task.dest / "launcher"

    createLauncher(
      sharedCp,
      cp.filterNot(sharedCp.toSet),
      mainClass,
      dest,
      windows = false,
      fast = true
    )

    PathRef(dest)
  }
  def windowsFastLauncher = Task {
    val mainClass = finalMainClass()
    val sharedCp  = launcherSharedClassPath().map(_.path)
    val cp        = launcherClassPath().map(_.path)
    val dest      = Task.dest / "launcher.bat"

    createLauncher(
      sharedCp,
      cp.filterNot(sharedCp.toSet),
      mainClass,
      dest,
      windows = true,
      fast = true
    )

    PathRef(dest)
  }
  def fastLauncher =
    if (isWindows) windowsFastLauncher
    else unixFastLauncher
}
