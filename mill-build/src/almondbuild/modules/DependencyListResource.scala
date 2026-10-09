package almondbuild.modules

import mill.*
import mill.api.*
import mill.javalib.*

/** Computes the dependencies users get from us, as `org:name:version` lines.
  *
  * Our modules are listed with the coordinates they're published with, rather than the internal
  * ones Mill resolves them with.
  */
trait UserDependencyList extends JavaModule {
  private def userPublishModules: Seq[PublishModule] =
    transitiveModuleDeps.collect {
      case mod: PublishModule => mod
    }
  private def userVendoringModules: Seq[VendoredDependencies] =
    transitiveModuleDeps.collect {
      case mod: VendoredDependencies => mod
    }

  def userDependencies = Task {
    val res = millResolver().resolution(
      Seq(coursierDependencyTask())
    )
    val external = res
      .orderedDependencies
      .map { dep =>
        (dep.module.organization.value, dep.module.name.value, dep.versionConstraint.asString)
      }
      .filter(_._1 != "mill-internal")
    val published = Task.traverse(userPublishModules)(_.artifactMetadata)().map { artifact =>
      (artifact.group, artifact.id, artifact.version)
    }
    val vendored = Task.traverse(userVendoringModules)(_.vendoredDependencies)().flatten.map {
      dep =>
        dep.split(':') match {
          case Array(org, name, ver) => (org, name, ver)
          case _                     => sys.error(s"Malformed vendored dependency: $dep")
        }
    }
    (external ++ published ++ vendored)
      .distinct
      .sorted
      .map {
        case (org, name, ver) =>
          s"$org:$name:$ver"
      }
      .mkString("\n")
  }
}

/** Ships the dependencies users get from us in our resources, for the kernel to leave them out of
  * the dependencies users add - those are already on their class path.
  *
  * The Scala compiler is among those dependencies, and its own dependencies vary among the Scala
  * versions of a binary version: we ship a list per Scala version we support (see
  * `almond.amm.UserDependencies` in scala-interpreter), along with the one we get with the Scala
  * version we're built with, for the kernels running other Scala versions.
  */
trait DependencyListResource extends AlmondCrossSbtModule with UserDependencyList {

  /** Our user dependencies, when the Scala version users run is forced to each of these */
  def userDependenciesByScalaVersion: T[Seq[(String, String)]]

  def depResourcesDir = Task {
    val dir = Task.dest / "dependency-resources"

    def write(name: String, content: String): Unit = {
      val f = dir / "almond" / name
      os.write.over(f, content.getBytes("UTF-8"), createFolders = true)
      System.err.println(s"Wrote $f")
    }

    val byScalaVersion = userDependenciesByScalaVersion()
    write("almond-user-dependencies.txt", userDependencies())
    for ((sv, content) <- byScalaVersion)
      write(s"almond-user-dependencies-$sv.txt", content)
    write("almond-user-dependencies-versions.txt", byScalaVersion.map(_._1).mkString("\n"))

    PathRef(dir)
  }
  def resources = Task {
    super.resources() ++ Seq(depResourcesDir())
  }
}
