package almondbuild.modules

import mill.scalalib._

trait AlmondSimpleModule
    extends SbtModule
    with AlmondRepositories
    with AlmondPublishModule
    with TransitiveSources
    with AlmondArtifactName
    with AlmondScalacOptions
