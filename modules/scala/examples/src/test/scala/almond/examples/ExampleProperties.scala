package almond.examples

import coursier.jvm.{JavaHome, JvmCache}

object ExampleProperties {
  lazy val directory = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.dir",
      sys.error("Expected almond.examples.dir to be set")
    )
    os.Path(pathStr)
  }
  lazy val outputDirectory = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.output-dir",
      sys.error("Expected almond.examples.output-dir to be set")
    )
    os.Path(pathStr)
  }
  lazy val launcher = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.launcher",
      sys.error("Expected almond.examples.launcher to be set")
    )
    os.Path(pathStr)
  }
  lazy val repoRoot = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.repo-root",
      sys.error("Expected almond.examples.repo-root to be set")
    )
    os.Path(pathStr)
  }
  lazy val uv = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.uv",
      sys.error("Expected almond.examples.uv to be set")
    )
    os.Path(pathStr)
  }
  lazy val scalaVersion =
    sys.props.getOrElse(
      "almond.examples.scala-version",
      sys.error("Expected almond.examples.scala-version to be set")
    )
  lazy val referenceScalaVersion =
    sys.props.getOrElse(
      "almond.examples.reference-scala-version",
      sys.error("Expected almond.examples.reference-scala-version to be set")
    )
  lazy val jupyterPath = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.jupyter-path",
      sys.error("Expected almond.examples.jupyter-path to be set")
    )
    os.Path(pathStr)
  }

  /** Environment variables making the JVM the kernel runs on the default one: the build passes
    * its coursier id, and we download it to the coursier cache if needed.
    */
  lazy val jvmEnv: Map[String, String] = {
    val id = sys.props.getOrElse(
      "almond.examples.jvm",
      sys.error("Expected almond.examples.jvm to be set")
    )
    val jvmCache = JvmCache().withDefaultIndex
    JavaHome().withCache(jvmCache)
      .environmentFor(id)
      .unsafeRun()(using jvmCache.archiveCache.cache.ec)
      .transientUpdates()
      .toMap
  }
}
