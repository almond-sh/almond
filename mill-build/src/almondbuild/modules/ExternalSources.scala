package almondbuild.modules

import mill.*
import mill.api.*

trait ExternalSources extends AlmondCrossSbtModule {
  // def allMvnDeps = Task((transitiveMvnDeps(): Seq[Dep]) ++ (scalaLibraryMvnDeps(): Seq[Dep]))
  def externalSources = Task {
    millResolver().classpath(
      Seq(coursierDependencyTask()),
      sources = true
    )
  }
}
