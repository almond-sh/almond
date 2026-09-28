package almondbuild

import coursier.jvm.{JavaHome, JvmCache}

import java.io.File

import mill.api.PathRef

object JavaHomes {

  /** Java home for the passed JVM id (like "21", or "temurin:21"), relying on the JVM management
    * capabilities of coursier: the JVM is downloaded to coursier's cache if needed.
    */
  def javaHome(id: String): os.Path = {
    val jvmCache = JvmCache().withDefaultIndex
    val ec       = jvmCache.archiveCache.cache.ec
    val home     = JavaHome().withCache(jvmCache).get(id).unsafeRun()(using ec)
    os.Path(home.getAbsoluteFile)
  }

  /** Environment variables making the JVM at `javaHome` the default one for sub-processes:
    * `JAVA_HOME`, and `PATH` with `javaHome/bin` prepended to its current value.
    */
  def environment(javaHome: os.Path): Map[String, String] = {
    // Environment variable names are case-insensitive on Windows, where PATH is often spelled
    // "Path": look the current value up ignoring case, and keep the existing spelling.
    val pathKey     = sys.env.keys.find(_.equalsIgnoreCase("PATH")).getOrElse("PATH")
    val currentPath = sys.env.get(pathKey).filter(_.nonEmpty).toSeq
    // Absolute paths: from a Mill task, `os.Path#toString` gives paths under the workspace
    // relative to the task sandbox, which subprocesses run from elsewhere can't resolve.
    val newPath =
      (PathRef.toResolvedPathString(javaHome / "bin") +: currentPath).mkString(File.pathSeparator)
    Map(
      "JAVA_HOME" -> PathRef.toResolvedPathString(javaHome),
      pathKey     -> newPath
    )
  }
}
