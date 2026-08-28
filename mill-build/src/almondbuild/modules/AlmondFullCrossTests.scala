package almondbuild.modules

import almondbuild.Deps
import mill.*
import mill.api.*
import mill.scalalib.*

/** Tests of a module published for a binary Scala version, built and run with one full Scala
  * version.
  *
  * Mill's `ScalaTests` assumes a test module shares the Scala version of the module it tests, and
  * takes the pieces that depend on it from that module. Ours is built with the oldest full Scala
  * version of its binary version, while its tests are built and run with each full Scala version we
  * support, so those need to be pointed back at the Scala version of the test.
  */
trait AlmondFullCrossTests extends Cross.Module[String] with AlmondTestModule
    // AlmondForcedScalaVersion overrides the resolution parameters we would otherwise inherit
    // from the module under test, which force the Scala version that module is built with
    with AlmondForcedScalaVersion {

  /** A module built with exactly our Scala version, to get the compiler bridge from. */
  def scalaVersionSpecificModule: ScalaModule

  def crossScalaVersion     = crossValue
  override def scalaVersion = Task(crossValue)

  override def scalaCompilerBridge = scalaVersionSpecificModule.scalaCompilerBridge()

  // super would give us the options of the module under test, which are meant for the Scala
  // version it is built with - "--release 8" isn't accepted by the Scala 3.8 compiler, say
  override def scalacOptions = Task {
    almondScalacOptions()
  }

  // The Scala compiler on the class path of the module under test is the one of the oldest
  // Scala version of its binary version - depend on ours, so that the tests driving it get it.
  override def mvnDeps = Task {
    super.mvnDeps() ++ Seq(Deps.scalaCompiler(scalaVersion()))
  }

}
