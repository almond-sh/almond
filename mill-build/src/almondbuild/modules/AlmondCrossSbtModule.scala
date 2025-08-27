package almondbuild.modules

import mill._
import mill.scalalib._

// Adapted from https://github.com/lihaoyi/mill/blob/0.9.3/scalalib/src/MiscModule.scala/#L80-L100
// and https://github.com/com-lihaoyi/mill/blob/c75e29c78cfc3c1e04776978bfc8e5697f8ca1aa/scalalib/src/mill/scalalib/CrossSbtModule.scala#L7
// Compared to the original code, we ensure `scalaVersion()` rather than `crossScalaVersion` is
// used when computing paths, as the former is always a valid Scala version,
// while the latter can be a 3.x version while we compile using Scala 2.x
// (and later rely on dotty compatibility to mix Scala 2 / Scala 3 modules).
trait AlmondCrossSbtModule extends SbtModule with CrossModuleBase {
  outer =>

  def extraScalaSources = Task.Sources {
    scalaVersionDirectoryNames.map(s =>
      PathRef(moduleDir / "src" / "main" / s"scala-$s")
    )
  }
  def sources = Task {
    super.sources() ++ extraScalaSources()
  }
  def extraTestScalaSources = Task.Sources {
    scalaVersionDirectoryNames.map(s =>
      PathRef(moduleDir / "src" / "test" / s"scala-$s")
    )
  }
  trait CrossSbtModuleTests extends SbtTests {
    def sources = Task {
      super.sources() ++ extraTestScalaSources()
    }
  }
  trait Tests extends CrossSbtModuleTests
}
