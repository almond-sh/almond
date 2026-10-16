package almondbuild.modules

import almondbuild.{Deps, Versions}
import mill.*
import mill.api.*
import mill.scalalib.*

trait AlmondRepositories extends JavaModule {

  def repositories = Task {
    val snapshots =
      if (hasSnapshotDeps()) Seq(AlmondRepositories.mavenSnapshots)
      else Nil
    super.repositories() ++ snapshots
  }

  /** Pins all the modules of our Ammonite fork to the snapshot build we depend on, when we depend
    * on a specific one.
    *
    * The POMs of a snapshot build reference the other modules of the same build with a plain
    * "-SNAPSHOT" version, which resolves to the latest snapshot - so without this, only the modules
    * we depend on directly would be pinned.
    */
  def depManagement = Task {
    val pinAmmonite = AlmondRepositories.isTimestampedSnapshot(Versions.ammonite)
    super.depManagement() ++ (if (pinAmmonite) Deps.ammoniteModules else Nil)
  }

  /** Us, and the modules whose class path we need to compile - that is, the ones whose dependencies
    * are resolved along ours, with our repositories.
    */
  private def selfAndDependencyModules: Seq[JavaModule] =
    Seq(this) ++ transitiveModuleCompileModuleDeps

  /** Whether we, or any module we depend on, has a snapshot dependency - in which case the snapshot
    * repository is needed for that dependency to be found.
    */
  def hasSnapshotDeps: T[Boolean] = Task {
    Task.traverse(selfAndDependencyModules) { mod =>
      Task.Anon {
        (mod.mvnDeps() ++ mod.compileMvnDeps())
          .exists(dep => AlmondRepositories.isSnapshot(dep.version))
      }
    }().exists(identity)
  }
}

object AlmondRepositories {
  def mavenSnapshots = "central:maven-snapshots"

  /** Whether a version is a specific snapshot build, like 3.1.1-20261009.100322-4 */
  def isTimestampedSnapshot(version: String): Boolean =
    version.matches(".*-\\d{8}\\.\\d{6}-\\d+")

  /** Whether a version is a snapshot one, either 3.1.1-SNAPSHOT, or a specific build of it */
  def isSnapshot(version: String): Boolean =
    version.endsWith("-SNAPSHOT") || isTimestampedSnapshot(version)
}
