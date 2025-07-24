package almondbuild.modules

import mill.*
import mill.api.*
import mill.scalalib.*

trait AlmondArtifactName extends SbtModule {
  def artifactNameParts =
    super.artifactNameParts()
      .dropWhile(_ == "scala")
      .dropWhile(_ == "shared")
      .take(1)
}
