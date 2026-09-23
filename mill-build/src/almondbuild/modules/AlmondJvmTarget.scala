package almondbuild.modules

import coursier.version.Version
import mill.*
import mill.api.*
import mill.scalalib.*

/** Targets the oldest JVM we support.
  *
  * Mixed in the modules whose class files we publish - either on their own, or, like
  * `logger-scala2-macros`, inside the JAR of another module.
  */
trait AlmondJvmTarget extends AlmondScalacOptions {
  def javacOptions = super.javacOptions() ++ Seq(
    "--release",
    "8"
  )
  def scalacOptions = Task {
    val sv = Version(scalaVersion())
    val extraOptions =
      if (sv >= Version("2.12.0") && sv <= Version("2.12.18"))
        Seq("-target:8")
      else if (sv < Version("3.8.0"))
        Seq("--release", "8")
      else
        Seq("--release", "17")
    super.scalacOptions() ++ extraOptions
  }
}
