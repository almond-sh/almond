package almond.amm

import java.io.InputStream

import scala.io.{Codec, Source}

/** The dependencies already on the class path of users, that the dependencies they add leave out.
  *
  * scala-kernel-api ships them as resources: one list per Scala version it's built for (its
  * dependencies, like the ones of the Scala compiler, vary among the Scala versions of a binary
  * version), and one for the Scala version it's built with.
  */
object UserDependencies {

  private def resourcePrefix  = "almond/almond-user-dependencies"
  private def defaultResource = s"$resourcePrefix.txt"

  private def readResource(name: String): Option[String] = {
    var is: InputStream = null
    try {
      is = Thread.currentThread().getContextClassLoader.getResourceAsStream(name)
      Option(is).map(Source.fromInputStream(_)(Codec.UTF8).mkString)
    }
    finally
      if (is != null)
        is.close()
  }

  /** The Scala versions scala-kernel-api ships a dependency list for */
  def knownScalaVersions(): Seq[String] =
    readResource(s"$resourcePrefix-versions.txt")
      .toSeq
      .flatMap(_.linesIterator)
      .map(_.trim)
      .filter(_.nonEmpty)

  private def binaryVersion(sv: String): String =
    if (sv.startsWith("2.")) sv.split('.').take(2).mkString(".")
    else sv.takeWhile(_ != '.')

  private def numbers(sv: String): Seq[Int] =
    sv.split("[.-]").toSeq.map(_.takeWhile(_.isDigit)).takeWhile(_.nonEmpty).map(_.toInt)

  private def lessOrEqual(a: String, b: String): Boolean = {
    val (a0, b0) = (numbers(a), numbers(b))
    a0.zipAll(b0, 0, 0).find { case (x, y) => x != y }.forall { case (x, y) => x < y }
  }

  /** The Scala version among `known` whose dependency list to use when running `scalaVersion`: that
    * one if it's known, else the closest earlier one of its binary version (the dependencies of the
    * upcoming Scala versions are more likely to be the ones of the latest one we know).
    */
  def pick(known: Seq[String], scalaVersion: String): Option[String] =
    if (known.contains(scalaVersion)) Some(scalaVersion)
    else {
      val candidates = known
        .filter(sv => binaryVersion(sv) == binaryVersion(scalaVersion))
        .filter(lessOrEqual(_, scalaVersion))
      if (candidates.isEmpty) None
      else Some(candidates.reduce((a, b) => if (lessOrEqual(a, b)) b else a))
    }

  /** The resource with the dependency list to use when running `scalaVersion` */
  def resourceName(scalaVersion: String): String =
    pick(knownScalaVersions(), scalaVersion)
      .fold(defaultResource)(sv => s"$resourcePrefix-$sv.txt")
}
