package almondbuild.modules

import mill._
import mill.scalalib._

trait AlmondArtifactName extends SbtModule {
  def artifactNameParts =
    super.artifactNameParts()
      .dropWhile(_ == "scala")
      .dropWhile(_ == "shared")
      .take(1)
}
