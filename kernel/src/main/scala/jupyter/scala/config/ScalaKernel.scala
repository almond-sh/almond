package jupyter.scala
package config

import java.io.File

import ammonite.interpreter.Classes
import com.github.alexarchambault.ivylight.{ClasspathFilter, IvyHelper, Resolver}
import com.typesafe.scalalogging.slf4j.LazyLogging
import jupyter.kernel.interpreter._

import scalaz.\/

object ScalaKernel extends InterpreterKernel with LazyLogging {
  val scalaVersion = scala.util.Properties.versionNumberString

  val dependencies = Seq(
    ("org.scala-lang", "scala-library", scalaVersion),
    ("com.github.alexarchambault", s"ammonite-shell-api_$scalaVersion", BuildInfo.ammoniteVersion)
  )

  val resolvers = Seq(
    Resolver.localRepo,
    Resolver.defaultMaven
  ) ++ {
    if (BuildInfo.ammoniteVersion endsWith "-SNAPSHOT")
      Seq(Resolver.sonatypeRepo("snapshots"))
    else
      Seq()
  }

  /*
   * Same hack as in ammonite-shell, see the comment there
   */
  lazy val packJarMap = Classes.defaultClassPath()._1.map(f => f.getName -> f).toMap
  def jarMap(f: File) = packJarMap.getOrElse(f.getName, f)

  lazy val (startJars, startDirs) =
    IvyHelper.resolve(dependencies, resolvers).toList
      .map(jarMap)
      .distinct
      .filter(_.exists())
      .partition(_.getName endsWith ".jar")

  lazy val startClassLoader: ClassLoader =
    new ClasspathFilter(Thread.currentThread().getContextClassLoader, (Classes.bootClasspath ++ startJars ++ startDirs).toSet)

  def apply() = \/.fromTryCatchNonFatal {
    ScalaInterpreter(startJars, startDirs, dependencies, jarMap, resolvers, startClassLoader)
  }
}
