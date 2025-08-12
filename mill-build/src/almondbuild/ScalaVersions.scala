package almondbuild

object ScalaVersions {
  def scala3Latest   = "3.6.3"
  def scala3Compat   = "3.3.4"
  def scala213       = "2.13.16"
  def scala212       = "2.12.20"
  val binaries       = Seq(scala3Compat, scala213, scala212)
  val scala2Binaries = Seq(scala213, scala212)
  val all = Seq(
    scala3Latest,
    "3.4.3",
    "3.3.5",
    scala3Compat,
    scala213,
    "2.13.15",
    "2.13.14",
    "2.13.13",
    "2.13.12",
    scala212,
    "2.12.19",
    "2.12.18"
  ).distinct

  def binary(sv: String) =
    if (sv.startsWith("2.12.")) scala212
    else if (sv.startsWith("2.13.")) scala213
    else scala3Compat

}
