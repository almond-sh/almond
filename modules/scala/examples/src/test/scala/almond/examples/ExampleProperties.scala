package almond.examples

object ExampleProperties {
  lazy val directory = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.dir",
      sys.error("Expected almond.examples.dir to be set")
    )
    os.Path(pathStr, os.pwd)
  }
  lazy val outputDirectory = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.output-dir",
      sys.error("Expected almond.examples.output-dir to be set")
    )
    os.Path(pathStr, os.pwd)
  }
  lazy val launcher = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.launcher",
      sys.error("Expected almond.examples.launcher to be set")
    )
    os.Path(pathStr, os.pwd)
  }
  lazy val repoRoot = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.repo-root",
      sys.error("Expected almond.examples.repo-root to be set")
    )
    os.Path(pathStr, os.pwd)
  }
  lazy val jupyterPath = {
    val pathStr = sys.props.getOrElse(
      "almond.examples.jupyter-path",
      sys.error("Expected almond.examples.jupyter-path to be set")
    )
    os.Path(pathStr, os.pwd)
  }
}
