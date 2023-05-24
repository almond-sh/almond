import mill._
import mill.scalalib._

object Versions {
  def ammonite      = "3.0.0-M0-23-f664d7ef"
  def caseApp       = "2.1.0-M24"
  def jsoniterScala = "2.13.5"
  def scalafmt      = "2.7.5"
}

implicit class DepOps(private val dep: Dep) {
  def applyBinaryVersion213_3(scalaVersion: String): Dep =
    dep.cross match {
      case cross: CrossVersion.Binary
          if scalaVersion.startsWith("3.") || scalaVersion.startsWith("2.13.") =>
        val compatSuffix =
          if (scalaVersion.startsWith("3.")) "_3"
          else "_" + scalaVersion.split('.').take(2).mkString(".")
        dep.copy(cross =
          CrossVersion.Constant(value = compatSuffix, platformed = dep.cross.platformed)
        )
      case _ => dep
    }
  def withDottyCompat(scalaVersion: String): Dep =
    dep.cross match {
      case cross: CrossVersion.Binary if scalaVersion.startsWith("3.") =>
        dep.copy(cross = CrossVersion.Constant(value = "_2.13", platformed = dep.cross.platformed))
      case _ => dep
    }
}

object Deps {
  def ammoniteCompiler(sv: String) =
    ivy"com.lihaoyi:ammonite-compiler_$sv:${Versions.ammonite}"
  def ammoniteRepl(sv: String) =
    if (sv.startsWith("2.")) ivy"com.lihaoyi:ammonite-repl_$sv:${Versions.ammonite}"
    else
      ivy"com.lihaoyi:ammonite-cross-$sv-repl_${ScalaVersions.cross2_3Version(sv)}:${Versions.ammonite}"
  def ammoniteReplApi(sv: String) =
    if (sv.startsWith("2.")) ivy"com.lihaoyi:ammonite-repl-api_$sv:${Versions.ammonite}"
    else
      ivy"com.lihaoyi:ammonite-cross-$sv-repl-api_${ScalaVersions.cross2_3Version(sv)}:${Versions.ammonite}"
  def ammoniteSpark      = ivy"sh.almond::ammonite-spark:0.13.9"
  def caseAppAnnotations = ivy"com.github.alexarchambault::case-app-annotations:${Versions.caseApp}"
  def caseApp            = ivy"com.github.alexarchambault::case-app:${Versions.caseApp}"
  def classPathUtil      = ivy"io.get-coursier::class-path-util:0.1.4"
  def collectionCompat   = ivy"org.scala-lang.modules::scala-collection-compat:2.9.0"
  def coursier           = ivy"io.get-coursier::coursier:2.1.4"
  def coursierApi        = ivy"io.get-coursier:interface:1.0.16"
  def expecty            = ivy"com.eed3si9n.expecty::expecty:0.16.0"
  def fs2(sv: String) =
    if (sv.startsWith("2.")) ivy"co.fs2::fs2-core:3.6.1" else ivy"co.fs2:fs2-core_2.13:3.6.1"
  def jansi  = ivy"org.fusesource.jansi:jansi:2.4.0"
  def jeromq = ivy"org.zeromq:jeromq:0.5.3"
  def jsoniterScalaCore =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterScala}"
  def jsoniterScalaMacros =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterScala}"
  def jvmRepr                  = ivy"com.github.jupyter:jvm-repr:0.4.0"
  def mdoc                     = ivy"org.scalameta::mdoc:2.3.7"
  def munit                    = ivy"org.scalameta::munit:0.7.29"
  def metabrowseServer         = ivy"org.scalameta:::metabrowse-server:0.2.9"
  def osLib                    = ivy"com.lihaoyi::os-lib:0.9.1"
  def pprint                   = ivy"com.lihaoyi::pprint:0.8.1"
  def scalafmtDynamic          = ivy"org.scalameta::scalafmt-dynamic:${Versions.scalafmt}"
  def scalapy                  = ivy"me.shadaj::scalapy-core:0.5.2"
  def scalaReflect(sv: String) = ivy"org.scala-lang:scala-reflect:$sv"
  def scalaRx                  = ivy"com.lihaoyi::scalarx:0.4.3"
  def scalatags                = ivy"com.lihaoyi::scalatags:0.12.0"
  def slf4jNop                 = ivy"org.slf4j:slf4j-nop:1.7.36"
  def utest                    = ivy"com.lihaoyi::utest:0.8.1"
}

object ScalaVersions {
  def scala3Latest = "3.2.2"
  def scala3Compat = "3.2.0"
  def cross2_3Version(sv: String) =
    if (sv.startsWith("3.0.") || sv.startsWith("3.1.")) "2.13.7"
    else "2.13.10"
  def scala213 = "2.13.10"
  def scala212 = "2.12.17"
  val binaries = Seq(scala3Compat, scala213, scala212)
  val all = Seq(
    scala3Latest,
    "3.2.1",
    scala3Compat,
    scala213,
    "2.13.9",
    "2.13.8",
    "2.13.7",
    "2.13.6",
    "2.13.5",
    "2.13.4",
    "2.13.3",
    "2.13.2",
    "2.13.1",
    scala212,
    "2.12.16",
    "2.12.15",
    "2.12.14",
    "2.12.13",
    "2.12.12",
    "2.12.11",
    "2.12.10",
    "2.12.9"
  ).distinct

  def binary(sv: String) =
    if (sv.startsWith("2.12.")) scala212
    else if (sv.startsWith("2.13.")) scala213
    else scala3Compat
}
