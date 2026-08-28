package almondbuild.modules

import mill.*
import mill.api.*
import mill.scalalib.*

trait AlmondRepositories extends CoursierModule {
  self: JavaModule =>

  def repositories = Task {
    val snapshots =
      if (hasSnapshotDeps()) Seq(AlmondRepositories.mavenSnapshots)
      else Nil
    super.repositories() ++ Seq("jitpack") ++ snapshots
  }

  /** Us, and the modules whose class path we need to compile - that is, the ones whose dependencies
    * are resolved along ours, with our repositories.
    */
  private def selfAndDependencyModules: Seq[JavaModule] =
    Seq(self) ++ transitiveModuleCompileModuleDeps

  /** Whether we, or any module we depend on, has a snapshot dependency - in which case the snapshot
    * repository is needed for that dependency to be found.
    */
  def hasSnapshotDeps: T[Boolean] = Task {
    Task.traverse(selfAndDependencyModules) { mod =>
      Task.Anon {
        (mod.mvnDeps() ++ mod.compileMvnDeps()).exists(_.version.endsWith("-SNAPSHOT"))
      }
    }().exists(identity)
  }
}

object AlmondRepositories {
  def mavenSnapshots = "central:maven-snapshots"
}
