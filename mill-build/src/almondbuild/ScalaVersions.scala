package almondbuild

object ScalaVersions {
  def scala3Latest   = "3.8.1"
  def scala3Lts      = "3.3.7"
  def scala213       = "2.13.18"
  def scala212       = "2.12.21"
  val binaries       = Seq(scala3Lts, scala213, scala212)
  val scala2Binaries = Seq(scala213, scala212)
  val all = Seq(
    scala3Latest,
    scala3Lts,
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

  def binary(sv: String) =
    if (sv.startsWith("2.12.")) scala212
    else if (sv.startsWith("2.13.")) scala213
    else scala3Lts

}
