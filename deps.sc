import mill._, scalalib._

object Versions {
  def ammonite      = "2.3.8-36-1cce53f3"
  def caseApp       = "2.0.6"
  def jsoniterScala = "2.7.2"
  def scalafmt      = "2.7.5"
}

object Deps {
  def ammoniteCompiler         = ivy"com.lihaoyi:::ammonite-compiler:${Versions.ammonite}"
  def ammoniteRepl             = ivy"com.lihaoyi:::ammonite-repl:${Versions.ammonite}"
  def ammoniteReplApi          = ivy"com.lihaoyi:::ammonite-repl-api:${Versions.ammonite}"
  def ammoniteSpark            = ivy"sh.almond::ammonite-spark:0.11.0"
  def caseAppAnnotations       = ivy"com.github.alexarchambault::case-app-annotations:${Versions.caseApp}"
  def caseApp                  = ivy"com.github.alexarchambault::case-app:${Versions.caseApp}"
  def collectionCompat         = ivy"org.scala-lang.modules::scala-collection-compat:2.4.4"
  def coursier                 = ivy"io.get-coursier::coursier:2.0.14"
  def coursierApi              = ivy"io.get-coursier:interface:1.0.3"
  def directories              = ivy"io.github.soc:directories:12"
  def fs2                      = ivy"co.fs2::fs2-core:2.5.4"
  def jansi                    = ivy"org.fusesource.jansi:jansi:1.18"
  def jeromq                   = ivy"org.zeromq:jeromq:0.5.2"
  def jsoniterScalaCore        = ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterScala}"
  def jsoniterScalaMacros      = ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterScala}"
  def jvmRepr                  = ivy"com.github.jupyter:jvm-repr:0.4.0"
  def mdoc                     = ivy"org.scalameta::mdoc:2.2.20"
  def metabrowseServer         = ivy"org.scalameta::metabrowse-server:0.2.3"
  def scalafmtDynamic          = ivy"org.scalameta::scalafmt-dynamic:${Versions.scalafmt}"
  def scalaReflect(sv: String) = ivy"org.scala-lang:scala-reflect:$sv"
  def scalaRx                  = ivy"com.lihaoyi::scalarx:0.4.3"
  def scalatags                = ivy"com.lihaoyi::scalatags:0.9.4"
  def slf4jNop                 = ivy"org.slf4j:slf4j-nop:1.8.0-beta4"
  def sparkSql                 = ivy"org.apache.spark::spark-sql:2.4.0"
  def utest                    = ivy"com.lihaoyi::utest:0.7.9"
}

object ScalaVersions {
  def scala213 = "2.13.4"
  def scala212 = "2.12.13"
  val binaries = Seq(scala213, scala212)
  val all = Seq(
    scala213, "2.13.3", "2.13.2", "2.13.1", "2.13.0",
    scala212, "2.12.12", "2.12.11", "2.12.10", "2.12.9", "2.12.8"
  )
}
