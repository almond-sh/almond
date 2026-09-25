package almondbuild.modules

import almondbuild.{Scala212_8JrtPatch, ScalaVersions}
import mill.*
import mill.api.*
import mill.scalalib.*

/** Runs the compiler of this module's Scala version on a JVM it supports, when the one Mill runs on
  * is too recent for it - see [[ScalaVersions.jvmVersionFor]]. Mill forks the tests and programs of
  * the module with that JVM too.
  */
trait AlmondScalacJvm extends ScalaModule {

  /** The Scala version of this module, known when the build is defined */
  def crossScalaVersion: String

  def jvmVersion = Task {
    ScalaVersions.jvmVersionFor(scalaVersion()).getOrElse(super.jvmVersion())
  }

  // Mill runs scalac in a separate process when we pick a JVM for it, which doesn't get the
  // options of .mill-jvm-opts: pass it the same stack size as the Mill daemon, the shapeless
  // macros of case-app overflow the default one on the older Scala 2 compilers. Mill takes the
  // options of that process from the -J-prefixed javac options.
  def javacOptions = Task {
    val stackSize =
      if (ScalaVersions.jvmVersionFor(scalaVersion()).isDefined) Seq("-J-Xss8m")
      else Nil
    super.javacOptions() ++ stackSize
  }

  /** Patched compiler classes, that go ahead of scalac's own on its class path */
  private def scalacPatches: Seq[JavaModule] =
    if (crossScalaVersion == Scala212_8JrtPatch.scalaVersion0) Seq(Scala212_8JrtPatch)
    else Nil

  def scalaCompilerClasspath = Task {
    Task.traverse(scalacPatches)(_.jar)() ++ super.scalaCompilerClasspath()
  }
}
