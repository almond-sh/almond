package almondbuild.modules

import mill.scalalib.*

trait AlmondSimpleModule
    extends SbtModule
    with AlmondRepositories
    with AlmondPublishModule
    with TransitiveSources
    with AlmondArtifactName
    with AlmondScalacOptions
