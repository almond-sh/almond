package almondbuild.modules

import mill.scalalib.*

trait AlmondSimpleModule
    extends SbtModule
    with AlmondRepositories
    with AlmondPublishModule
    with TransitiveSources
    with AlmondArtifactName
    with AlmondScalacOptions {

  // Getting transient errors upon incremental compilation without this
  def scalacOptions = super.scalacOptions
}
