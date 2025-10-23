package almondbuild.modules

import mill.*
import mill.api.*

trait AlmondModule
    extends AlmondCrossSbtModule
    with AlmondRepositories
    with AlmondPublishModule
    with TransitiveSources
    with AlmondArtifactName
    with AlmondScalacOptions {

  // from https://github.com/VirtusLab/scala-cli/blob/cf77234ab981332531cbcb0d6ae565de009ae252/build.sc#L501-L522
  // pin scala3-library suffix, so that 2.13 modules can have us as moduleDep fine
  def mandatoryMvnDeps = Task {
    super.mandatoryMvnDeps().map { dep =>
      val isScala3Lib =
        dep.dep.module.organization.value == "org.scala-lang" &&
        dep.dep.module.name.value == "scala3-library" &&
        (dep.cross match {
          case _: CrossVersion.Binary => true
          case _                      => false
        })
      if (isScala3Lib)
        dep.copy(
          dep = dep.dep.withModule(
            dep.dep.module.withName(
              coursier.ModuleName(dep.dep.module.name.value + "_3")
            )
          ),
          cross = CrossVersion.empty(dep.cross.platformed)
        )
      else dep
    }
  }

  // Getting transient errors upon incremental compilation without this
  def artifactNameParts = super.artifactNameParts
  def scalacOptions     = super.scalacOptions
}
