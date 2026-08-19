package almondbuild

import coursier.version.Version

object ScalaVersions {
  def scala3Latest   = "3.8.4"
  def scala3Compat   = "3.3.8"
  def scala213       = "2.13.18"
  def scala212       = "2.12.21"
  val binaries       = Seq(scala3Compat, scala213, scala212)
  val scala2Binaries = Seq(scala213, scala212)
  val all            = Seq(
    scala3Latest,
    scala3Compat,
    scala213,
    "2.13.17",
    "2.13.16",
    "2.13.15",
    "2.13.14",
    scala212,
    "2.12.20",
    "2.12.19",
    "2.12.18"
  ).distinct
  val ci = {
    val (scala2, scala3) = all.partition(_.startsWith("2."))
    val scala2Latest     = scala2
      .groupBy(_.split('.').take(2).mkString("."))
      .values
      .flatMap(_.sortBy(Version(_))(using Ordering[Version].reverse).take(2))
      .toSeq
    (scala3 ++ scala2Latest).sortBy(Version(_))(using Ordering[Version].reverse)
  }

  def binary(sv: String) =
    if (sv.startsWith("2.12.")) scala212
    else if (sv.startsWith("2.13.")) scala213
    else scala3Compat

}
