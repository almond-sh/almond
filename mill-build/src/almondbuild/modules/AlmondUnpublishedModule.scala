package almondbuild.modules

trait AlmondUnpublishedModule
    extends AlmondCrossSbtModule
    with AlmondRepositories
    with TransitiveSources
    with AlmondArtifactName
    with AlmondScalacOptions
