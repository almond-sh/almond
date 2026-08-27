package almondbuild

import coursier.version.Version

object ScalaVersions {
  def scala3Latest = "3.8.4"
  def scala3Compat = "3.3.8"
  def scala213     = "2.13.18"
  def scala212     = "2.12.21"

  // Published modules are built once per binary Scala version, with the oldest full Scala
  // version we support for it: Scala 2 patch releases are only forward binary compatible, so
  // building scala-interpreter_2.13 with 2.13.14 gives an artifact all the 2.13.x versions we
  // support can run, while building it with 2.13.18 wouldn't. For Scala 3, the LTS plays that
  // role, its TASTy files being readable by all the later Scala 3 versions we support.
  def scala213Oldest = "2.13.14"
  def scala212Oldest = "2.12.18"

  val binaries       = Seq(scala3Compat, scala213Oldest, scala212Oldest)
  val scala2Binaries = Seq(scala213Oldest, scala212Oldest)
  val all = Seq(
    scala3Latest,
    scala3Compat,
    scala213,
    "2.13.17",
    "2.13.16",
    "2.13.15",
    scala213Oldest,
    scala212,
    "2.12.20",
    "2.12.19",
    scala212Oldest
  ).distinct
  val ci = {
    val (scala2, scala3) = all.partition(_.startsWith("2."))
    val scala2Latest = scala2
      .groupBy(_.split('.').take(2).mkString("."))
      .values
      .flatMap(_.sortBy(Version(_))(using Ordering[Version].reverse).take(2))
      .toSeq
    (scala3 ++ scala2Latest).sortBy(Version(_))(using Ordering[Version].reverse)
  }

  /** The [[binaries]] entry a full Scala version's modules are built under. */
  def binary(sv: String) =
    if (sv.startsWith("2.12.")) scala212Oldest
    else if (sv.startsWith("2.13.")) scala213Oldest
    else scala3Compat

  /** The suffix the modules we publish carry for a full Scala version. */
  def binarySuffix(sv: String) =
    if (sv.startsWith("3.")) "3"
    else sv.split('.').take(2).mkString(".")

  /** The full Scala versions the tests of a [[binaries]] module instance run with. */
  def fullVersionsFor(binaryScalaVersion: String): Seq[String] =
    all.filter(binary(_) == binaryScalaVersion)

}
