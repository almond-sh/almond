package almondbuild.modules

import mill.*
import mill.api.*

trait ExternalSources extends AlmondCrossSbtModule {
  // def allMvnDeps = Task((transitiveMvnDeps(): Seq[Dep]) ++ (scalaLibraryMvnDeps(): Seq[Dep]))
  def externalSources = Task {
    resolveDeps(Task.Anon(transitiveCompileMvnDeps() ++ transitiveMvnDeps()), sources = true)()
  }
}
