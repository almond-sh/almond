package almondbuild.modules

import almondbuild.Mima
import com.github.lolgab.mill.mima._
import mill._
import mill.javalib._

trait AlmondMima extends com.github.lolgab.mill.mima.Mima with PublishModule {
  def mimaPreviousVersions = Mima.binaryCompatibilityVersions()

  // same as https://github.com/lolgab/mill-mima/blob/de28f3e9fbe92867f98e35f8dfd3c3a777cc033d/mill-mima/src/com/github/lolgab/mill/mima/Mima.scala#L29-L44
  // except we're ok if mimaPreviousVersions is empty
  def mimaPreviousArtifacts = Task {
    val versions = mimaPreviousVersions().distinct
    mill.api.Result.Success(
      Agg.from(
        versions.map(version =>
          ivy"${pomSettings().organization}:${artifactId()}:$version"
        )
      )
    )
  }
}
