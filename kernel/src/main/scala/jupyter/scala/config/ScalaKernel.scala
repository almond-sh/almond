package jupyter.scala
package config

import java.io.File

import ammonite.shell.ReplAPI
import com.github.alexarchambault.ivylight.{IvyHelper, ResolverHelpers}
import com.typesafe.scalalogging.slf4j.LazyLogging
import jupyter.kernel.interpreter._

import scalaz.\/

object ScalaKernel extends InterpreterKernel with LazyLogging {
  val scalaVersion = scala.util.Properties.versionNumberString
  val scalaBinaryVersion = scalaVersion.split('.').take(2).mkString(".")

  val dependencies = Seq(
    ("org.scala-lang", "scala-library", scalaVersion),
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
    IvyHelper.resolve(dependencies, resolvers) .distinct

  lazy val (startJarClassPath, startFileClassPath) =
    (bootClassPath ++ baseClassPath)
      .filter(_.exists())
      .partition(_.getName endsWith ".jar")

  def interpreter(classLoader: Option[ClassLoader]) = \/.fromTryCatchNonFatal {
    ScalaInterpreter(
      startJarClassPath,
      startFileClassPath,
      dependencies,
      resolvers,
      classLoader getOrElse classOf[ReplAPI].getClassLoader
    )
  }
}
