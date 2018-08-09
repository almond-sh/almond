
import sbt._

object Deps {

  def ammoniteRepl = ("com.lihaoyi" % "ammonite-repl" % "1.1.2-21-df41270").cross(CrossVersion.full)
  def ammoniteSpark = "sh.almond" %% "ammonite-spark" % "0.1.0"
  def argonautShapeless = "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M8"
  def caseApp = "com.github.alexarchambault" %% "case-app" % "2.0.0-M2"
  def fs2 = "co.fs2" %% "fs2-core" % "0.10.5"
  def jeromq = "org.zeromq" % "jeromq" % "0.4.3"
  def logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
  def scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0"
  def sparkSql = "org.apache.spark" %% "spark-sql" % "2.0.2"
  def utest = "com.lihaoyi" %% "utest" % "0.6.4"

}
