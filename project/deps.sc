import mill._
import mill.scalalib._

object Versions {
  def ammonite      = "3.0.2"
  def caseApp       = "2.1.0-M30"
  def coursier      = "2.1.25-M2"
  def jsoniterScala = "2.13.5"
  def scalafmt      = "3.9.8"
  def scalafmtJava8 = "3.7.15"
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
  def ammoniteCompiler = ivy"com.lihaoyi:::ammonite-compiler:${Versions.ammonite}"
  def ammoniteRepl     =
    ivy"com.lihaoyi:::ammonite-repl:${Versions.ammonite}"
      .exclude(("org.scalameta", "semanticdb-shared_*"))
  def ammoniteReplApi    = ivy"com.lihaoyi:::ammonite-repl-api:${Versions.ammonite}"
  def ammoniteSpark      = ivy"sh.almond::ammonite-spark:0.14.0-RC8"
  def caseAppAnnotations = ivy"com.github.alexarchambault::case-app-annotations:${Versions.caseApp}"
  def caseApp            = ivy"com.github.alexarchambault::case-app:${Versions.caseApp}"
  def classPathUtil      = ivy"io.get-coursier::class-path-util:0.1.4"
  def collectionCompat   = ivy"org.scala-lang.modules::scala-collection-compat:2.13.0"
  def coursier           =
    ivy"io.get-coursier::coursier:${Versions.coursier}".exclude(("org.slf4j", "slf4j-api"))
  def coursierApi         = ivy"io.get-coursier:interface:1.0.29-M1"
  def coursierLauncher    = ivy"io.get-coursier:coursier-launcher_2.13:${Versions.coursier}"
  def coursierVersions    = ivy"io.get-coursier::versions:0.5.1"
  def dependencyInterface = ivy"io.get-coursier::dependency-interface:0.3.2"
  def directiveHandler    = ivy"io.github.alexarchambault.scala-cli::directive-handler:0.1.4"
  def expecty             = ivy"com.eed3si9n.expecty::expecty:0.17.0"
  def fansi               = ivy"com.lihaoyi::fansi:0.5.0"
  def fs2                 = ivy"co.fs2::fs2-core:3.11.0"
  def jansi               = ivy"org.fusesource.jansi:jansi:2.4.1"
  def jeromq              = ivy"org.zeromq:jeromq:0.5.4"
  def jsoniterScalaCore   =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterScala}"
  def jsoniterScalaMacros =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterScala}"
  def jvmRepr                  = ivy"com.github.jupyter:jvm-repr:0.4.0"
  def mdoc                     = ivy"org.scalameta::mdoc:2.6.4"
  def mtags                    = ivy"org.scalameta:::mtags:1.5.1"
  def munit                    = ivy"org.scalameta::munit:1.1.0"
  def osLib                    = ivy"com.lihaoyi::os-lib:0.11.4"
  def pprint                   = ivy"com.lihaoyi::pprint:0.9.0"
  def scalafmtDynamic          = ivy"org.scalameta::scalafmt-dynamic:${Versions.scalafmt}"
  def scalaparse               = ivy"com.lihaoyi::scalaparse:3.1.1"
  def scalapy                  = ivy"me.shadaj::scalapy-core:0.5.2"
  def scalaReflect(sv: String) = ivy"org.scala-lang:scala-reflect:$sv"
  def scalaRx                  = ivy"com.lihaoyi::scalarx:0.4.3"
  def scalatags                = ivy"com.lihaoyi::scalatags:0.13.1"
  def slf4jNop                 = ivy"org.slf4j:slf4j-nop:1.7.36"
  def sourcecode               = ivy"com.lihaoyi::sourcecode:0.3.0"
  def testUtil                 = ivy"io.github.alexarchambault::test-util:0.1.6"
  def upickle                  =
    ivy"com.lihaoyi::upickle:3.1.4" // trying to use the same version as Ammonite, to avoid bin compat issues
  def utest = ivy"com.lihaoyi::utest:0.8.5"
}

object ScalaVersions {
  def scala3Latest   = "3.6.3"
  def scala3Compat   = "3.3.4"
  def scala213       = "2.13.16"
  def scala212       = "2.12.20"
  val binaries       = Seq(scala3Compat, scala213, scala212)
  val scala2Binaries = Seq(scala213, scala212)
  val all            = Seq(
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
