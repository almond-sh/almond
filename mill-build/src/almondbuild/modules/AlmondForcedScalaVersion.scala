package almondbuild.modules

import mill.*
import mill.api.*
import mill.javalib.api.JvmWorkerUtil
import mill.scalalib.*

/** A module whose Scala artifacts are all forced to its own Scala version.
  *
  * Mill forces the ones whose artifact name it can spell out without the Scala 3 suffix, which
  * leaves `scala3-compiler_3` and `scala3-library_3` at whatever version the modules we depend on
  * pull.
  */
trait AlmondForcedScalaVersion extends ScalaModule {
  def resolutionParams = Task.Anon {
    val sv       = scalaVersion()
    val org      = coursier.Organization(JvmWorkerUtil.scalaOrganization(sv))
    val suffixes = if (JvmWorkerUtil.isScala3(sv)) Seq("", "_3") else Seq("")
    super.resolutionParams().addForceVersion0(
      Lib.scalaArtifacts(sv).toSeq.sorted.flatMap { name =>
        suffixes.map { suffix =>
          coursier.Module(org, coursier.ModuleName(name + suffix), Map.empty) ->
            coursier.version.VersionConstraint(sv)
        }
      }*
    )
  }
}
