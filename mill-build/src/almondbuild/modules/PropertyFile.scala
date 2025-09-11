package almondbuild.modules

import almondbuild.Deps
import mill.*
import mill.api.*

trait PropertyFile extends AlmondPublishModule {

  def propertyFilePath: String
  def propertyExtra: T[Seq[(String, String)]] = Task(Seq.empty[(String, String)])

  def props = Task {
    import sys.process._

    val dir = Task.dest / "property-resources"
    val ver = publishVersion()

    // FIXME Only set if ammonite-spark is available for the current scala version?
    val ammSparkVer = Deps.ammoniteSpark.dep.versionConstraint.asString

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
  def propResourcesDir = Task {
    val dir = Task.dest / "property-resources"
    val f   = dir / propertyFilePath.split('/').toSeq

    val content = props().getBytes("UTF-8")

    os.write.over(f, content, createFolders = true)
    System.err.println(s"Wrote $f")

    PathRef(dir)
  }
  def resources = Task {
    super.resources() ++ Seq(propResourcesDir())
  }
}
