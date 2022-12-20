import mill._
import mill.scalalib._

object Versions {
  def ammonite      = "2.5.5-17-df243e14"
  def caseApp       = "2.0.6"
  def jsoniterScala = "2.13.5"
  def scalafmt      = "2.7.5"
}

implicit class DepOps(private val dep: Dep) {
  def applyBinaryVersion213_3(scalaVersion: String): Dep =
    dep.cross match {
      case cross: CrossVersion.Binary if scalaVersion.startsWith("3.") || scalaVersion.startsWith("2.13.") =>
        val compatSuffix =
          if (scalaVersion.startsWith("3.")) "_3"
          else "_" + scalaVersion.split('.').take(2).mkString(".")
        dep.copy(cross = CrossVersion.Constant(value = compatSuffix, platformed
= dep.cross.platformed))
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
    else ivy"com.lihaoyi:ammonite-cross-$sv-repl_${ScalaVersions.cross2_3Version}:${Versions.ammonite}"
  def ammoniteReplApi(sv: String) =
    if (sv.startsWith("2.")) ivy"com.lihaoyi:ammonite-repl-api_$sv:${Versions.ammonite}"
    else ivy"com.lihaoyi:ammonite-cross-$sv-repl-api_${ScalaVersions.cross2_3Version}:${Versions.ammonite}"
  def ammoniteSpark            = ivy"sh.almond::ammonite-spark:0.13.5"
  def caseAppAnnotations       = ivy"com.github.alexarchambault::case-app-annotations:${Versions.caseApp}"
  def caseApp                  = ivy"com.github.alexarchambault::case-app:${Versions.caseApp}"
  def collectionCompat         = ivy"org.scala-lang.modules::scala-collection-compat:2.8.1"
  def coursier                 = ivy"io.get-coursier::coursier:2.0.14"
  def coursierApi              = ivy"io.get-coursier:interface:1.0.12"
  def directories              = ivy"io.github.soc:directories:12"
  def fs2                      = ivy"co.fs2::fs2-core:2.5.11"
  def jansi                    = ivy"org.fusesource.jansi:jansi:2.4.0"
  def jeromq                   = ivy"org.zeromq:jeromq:0.5.2"
  def jsoniterScalaCore        = ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterScala}"
  def jsoniterScalaMacros      = ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterScala}"
  def jvmRepr                  = ivy"com.github.jupyter:jvm-repr:0.4.0"
  def mdoc                     = ivy"org.scalameta::mdoc:2.3.6"
  def metabrowseServer         = ivy"org.scalameta:::metabrowse-server:0.2.9"
  def scalafmtDynamic          = ivy"org.scalameta::scalafmt-dynamic:${Versions.scalafmt}"
  def scalapy                  = ivy"me.shadaj::scalapy-core:0.5.2"
  def scalaReflect(sv: String) = ivy"org.scala-lang:scala-reflect:$sv"
  def scalaRx                  = ivy"com.lihaoyi::scalarx:0.4.3"
  def scalatags                = ivy"com.lihaoyi::scalatags:0.12.0"
  def slf4jNop                 = ivy"org.slf4j:slf4j-nop:2.0.5"
  def sparkSql                 = ivy"org.apache.spark::spark-sql:2.4.0"
  def utest                    = ivy"com.lihaoyi::utest:0.8.1"
}

object ScalaVersions {
  def scala3   = "3.1.3"
  def cross2_3Version = "2.13.7"
  def scala213 = "2.13.10"
  def scala212 = "2.12.17"
  val binaries = Seq(scala3, scala213, scala212)
  val all = Seq(
    scala3, "3.1.2", "3.1.1", "3.1.0",
    scala213, "2.13.9", "2.13.8", "2.13.7", "2.13.6", "2.13.5", "2.13.4", "2.13.3", "2.13.2", "2.13.1",
    scala212, "2.12.16", "2.12.15", "2.12.14", "2.12.13", "2.12.12", "2.12.11", "2.12.10", "2.12.9"
  )
}
