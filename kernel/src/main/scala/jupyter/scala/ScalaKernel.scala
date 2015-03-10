package jupyter
package scala

import java.io.File

import com.typesafe.scalalogging.slf4j.LazyLogging
import bridge.Bridge
import kernel.interpreter._
import kernel.BuildInfo

import scalaz.\/

object ScalaKernel extends InterpreterKernel with LazyLogging {
  val scalaBinaryVersion = BuildInfo.scalaVersion.split('.').take(2).mkString(".")

  val dependencies = Seq(
    ("org.scala-lang", "scala-library", BuildInfo.scalaVersion),
    ("com.github.alexarchambault.jupyter", s"jupyter-bridge_$scalaBinaryVersion", BuildInfo.version)
  )


  val resolvers = Seq(
    ResolverHelpers.localRepo,
    ResolverHelpers.sonatypeRepo("releases"),
    ResolverHelpers.sonatypeRepo("snapshots"),
    ResolverHelpers.mavenLocal,
    ResolverHelpers.defaultMaven
  )

  lazy val bootClassPath =
    System.getProperty("sun.boot.class.path")
      .split(File.pathSeparator).map(new File(_))

  lazy val baseClassPath =
    dependencies.flatMap {
      case (org, name, rev) =>
        IvyHelper.resolveArtifact(org, name, rev, resolvers)
    } .distinct

  lazy val (startJarClassPath, startFileClassPath) =
    (bootClassPath ++ baseClassPath)
      .filter(_.exists())
      .partition(_.getName endsWith ".jar")

  def interpreter(classLoader: Option[ClassLoader]) = \/.fromTryCatchNonFatal {
    ScalaInterpreter(
      startJarClassPath,
      startFileClassPath,
      classLoader getOrElse Bridge.classLoader,
      resolvers
    )
  }
}
