package almondbuild

import mill.*
import mill.api.*
import mill.scalalib.*

/** A patched JrtClassPath for scalac 2.12.8, letting it expand macros on JDK 15+.
  *
  * The macro class loader of scalac 2.12.8 is built from the URLs of its class path, and its
  * JrtClassPath hands it the URI of the jrt:/packages directory, which JDK 15+ reject: any macro
  * expansion fails with "/packages cannot be represented as URI". 2.12.9 fixed that, but 2.12.8 is
  * the oldest Scala 2.12 version our Ammonite fork supports, and the one the modules we publish for
  * 2.12 are built with. Mill only runs scalac on JDK 17+, so we compile this copy of 2.12.8's
  * JrtClassPath with the fix of 2.12.9, and put it ahead of scala-compiler on the class path of
  * scalac 2.12.8 - see [[almondbuild.modules.AlmondScalacJvm]].
  *
  * Kernels running Scala 2.12.8 aren't affected: Ammonite loads macros with its own class loader,
  * rather than the one scalac builds from its class path.
  */
object Scala212_8JrtPatch extends ExternalModule with ScalaModule {
  lazy val millDiscover = Discover[this.type]

  def scalaVersion0 = "2.12.8"
  def scalaVersion  = scalaVersion0
  // The oldest JVM Mill runs scalac on - the one ScalaVersions.jvmVersionFor picks for 2.12.8
  def jvmVersion = "17"
  def sources = Task.Sources(
    BuildCtx.workspaceRoot / "mill-build" / "scalac-patches" / "scala-2.12.8"
  )
  def compileMvnDeps = Seq(Deps.scalaCompiler(scalaVersion0))
}
