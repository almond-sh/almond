package almondbuild.modules

import mill._

trait ExternalSources extends AlmondCrossSbtModule {
  // def allMvnDeps = Task((transitiveIvyDeps(): Seq[Dep]) ++ (scalaLibraryMvnDeps(): Seq[Dep]))
  def externalSources = Task {
    resolveDeps(Task.Anon(transitiveCompileIvyDeps() ++ transitiveIvyDeps()), sources = true)()
  }
}
