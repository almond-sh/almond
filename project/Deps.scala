
import sbt._
import sbt.Def.setting
import sbt.Keys.scalaVersion

object Deps {

  object Versions {
    def ammonite = "1.5.0"
  }

  def ammoniteRepl = ("com.lihaoyi" % "ammonite-repl" % Versions.ammonite).cross(CrossVersion.full)
  def ammoniteSpark = "sh.almond" %% "ammonite-spark" % "0.2.0"
  def argonautShapeless = "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M8"
  def caseAppAnnotations = "com.github.alexarchambault" %% "case-app-annotations" % "2.0.0-M5"
  def caseApp = "com.github.alexarchambault" %% "case-app" % "2.0.0-M5"
  def fs2 = "co.fs2" %% "fs2-core" % "0.10.5"
  def jeromq = "org.zeromq" % "jeromq" % "0.4.3"
  def metabrowseServer = ("org.scalameta" %% "metabrowse-server" % "0.2.0").exclude("org.slf4j", "slf4j-simple")
  def scalaReflect = setting("org.scala-lang" % "scala-reflect" % scalaVersion.value)
  def scalaRx = "com.lihaoyi" %% "scalarx" % "0.4.0"
  def scalatags = "com.lihaoyi" %% "scalatags" % "0.6.7"
  def slf4jNop = "org.slf4j" % "slf4j-nop" % "1.7.25"
  def sparkSql = "org.apache.spark" %% "spark-sql" % "2.0.2"
  def utest = "com.lihaoyi" %% "utest" % "0.6.6"

}
