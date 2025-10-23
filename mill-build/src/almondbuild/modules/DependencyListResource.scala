package almondbuild.modules

import mill.*
import mill.api.*

trait DependencyListResource extends AlmondCrossSbtModule {
  def userDependencies = Task {
    val res = millResolver().resolution(
      Seq(coursierDependencyTask())
    )
    res
      .orderedDependencies
      .map { dep =>
        (dep.module.organization.value, dep.module.name.value, dep.versionConstraint.asString)
      }
      .distinct
      .sorted
      .map {
        case (org, name, ver) =>
          s"$org:$name:$ver"
      }
      .mkString("\n")
  }
  def depResourcesDir = Task {
    val content = userDependencies().getBytes("UTF-8")

    val dir = Task.dest / "dependency-resources"
    val f   = dir / "almond" / "almond-user-dependencies.txt"

    os.write.over(f, content, createFolders = true)
    System.err.println(s"Wrote $f")

    PathRef(dir)
  }
  def resources = Task {
    super.resources() ++ Seq(depResourcesDir())
  }
}
