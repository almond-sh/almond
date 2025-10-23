package almondbuild

import mill.*
import mill.api.*
import mill.scalalib.*

object Deps {

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
          dep.copy(cross =
            CrossVersion.Constant(value = "_2.13", platformed = dep.cross.platformed)
          )
        case _ => dep
      }
  }

  def ammoniteCompiler = mvn"com.lihaoyi:::ammonite-compiler:${Versions.ammonite}"
  def ammoniteRepl =
    mvn"com.lihaoyi:::ammonite-repl:${Versions.ammonite}"
      .exclude(("org.scalameta", "semanticdb-shared_*"))
  def ammoniteReplApi    = mvn"com.lihaoyi:::ammonite-repl-api:${Versions.ammonite}"
  def ammoniteSpark      = mvn"sh.almond::ammonite-spark:0.14.0-RC8"
  def caseAppAnnotations = mvn"com.github.alexarchambault::case-app-annotations:${Versions.caseApp}"
  def caseApp            = mvn"com.github.alexarchambault::case-app:${Versions.caseApp}"
  def classPathUtil      = mvn"io.get-coursier::class-path-util:0.1.4"
  def collectionCompat   = mvn"org.scala-lang.modules::scala-collection-compat:2.14.0"
  def coursier =
    mvn"io.get-coursier::coursier:${Versions.coursier}".exclude(("org.slf4j", "slf4j-api"))
  def coursierApi         = mvn"io.get-coursier:interface:1.0.29-M2"
  def coursierLauncher    = mvn"io.get-coursier:coursier-launcher_2.13:${Versions.coursier}"
  def coursierVersions    = mvn"io.get-coursier::versions:0.5.1"
  def dependencyInterface = mvn"io.get-coursier::dependency-interface:0.3.2"
  def directiveHandler    = mvn"io.github.alexarchambault.scala-cli::directive-handler:0.1.4"
  def expecty             = mvn"com.eed3si9n.expecty::expecty:0.17.0"
  def fansi               = mvn"com.lihaoyi::fansi:0.5.1"
  def fs2                 = mvn"co.fs2::fs2-core:3.12.2"
  def jansi               = mvn"org.fusesource.jansi:jansi:2.4.2"
  def jeromq              = mvn"org.zeromq:jeromq:0.5.4"
  def jsoniterScalaCore =
    mvn"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterScala}"
  def jsoniterScalaMacros =
    mvn"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterScala}"
  def jvmRepr                  = mvn"com.github.jupyter:jvm-repr:0.4.0"
  def mdoc                     = mvn"org.scalameta::mdoc:2.8.0"
  def mtags                    = mvn"org.scalameta:::mtags:1.6.3"
  def munit                    = mvn"org.scalameta::munit:1.2.1"
  def osLib                    = mvn"com.lihaoyi::os-lib:0.11.5"
  def pprint                   = mvn"com.lihaoyi::pprint:0.9.0"
  def scalafmtDynamic          = mvn"org.scalameta::scalafmt-dynamic:${Versions.scalafmt}"
  def scalaparse               = mvn"com.lihaoyi::scalaparse:3.1.1"
  def scalapy                  = mvn"me.shadaj::scalapy-core:0.5.2"
  def scalaReflect(sv: String) = mvn"org.scala-lang:scala-reflect:$sv"
  def scalaRx                  = mvn"com.lihaoyi::scalarx:0.4.3"
  def scalatags                = mvn"com.lihaoyi::scalatags:0.13.1"
  def slf4jNop                 = mvn"org.slf4j:slf4j-nop:1.7.36"
  def sourcecode               = mvn"com.lihaoyi::sourcecode:0.3.0"
  def testUtil                 = mvn"io.github.alexarchambault::test-util:0.1.7"
  def upickle =
    mvn"com.lihaoyi::upickle:3.1.4" // trying to use the same version as Ammonite, to avoid bin compat issues
  def utest = mvn"com.lihaoyi::utest:0.9.1"
}
