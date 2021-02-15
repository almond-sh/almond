
import sbt._
import sbt.Def.setting
import sbt.Keys.scalaVersion

object Deps {

  object Versions {
    def ammonite = "2.3.8-4-88785969"
    def caseApp = "2.0.4"
    def jsoniterScala = "2.6.2"
    def scalafmt = "2.7.5"
  }

  def ammoniteRepl = setting(("com.lihaoyi" % "ammonite-repl" % Versions.ammonite).cross(CrossVersion.full))
  def ammoniteReplApi = setting(("com.lihaoyi" % "ammonite-repl-api" % Versions.ammonite).cross(CrossVersion.full))
  def ammoniteSpark = "sh.almond" %% "ammonite-spark" % "0.10.2"
  def caseAppAnnotations = "com.github.alexarchambault" %% "case-app-annotations" % Versions.caseApp
  def caseApp = "com.github.alexarchambault" %% "case-app" % Versions.caseApp
  def collectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.3.2"
  def coursier = "io.get-coursier" %% "coursier" % "2.0.12"
  def coursierApi = "io.get-coursier" % "interface" % "1.0.2"
  def directories = "io.github.soc" % "directories" % "12"
  def fs2 = "co.fs2" %% "fs2-core" % "2.5.0"
  def jansi = "org.fusesource.jansi" % "jansi" % "1.18"
  def jeromq = "org.zeromq" % "jeromq" % "0.5.2"
  def jsoniterScalaCore = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % Versions.jsoniterScala
  def jsoniterScalaMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % Versions.jsoniterScala
  def jvmRepr = "com.github.jupyter" % "jvm-repr" % "0.4.0"
  def metabrowseServer = "org.scalameta" %% "metabrowse-server" % "0.2.3"
  def scalafmtDynamic = "org.scalameta" %% "scalafmt-dynamic" % Versions.scalafmt
  def scalaReflect = setting("org.scala-lang" % "scala-reflect" % scalaVersion.value)
  def scalaRx = "com.lihaoyi" %% "scalarx" % "0.4.3"
  def scalatags = "com.lihaoyi" %% "scalatags" % "0.9.3"
  def slf4jNop = "org.slf4j" % "slf4j-nop" % "1.8.0-beta4"

  def sparkSql = "org.apache.spark" %% "spark-sql" % "2.4.0"

  def utest = "com.lihaoyi" %% "utest" % "0.7.7"
}
