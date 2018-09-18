import sbt._
import sbt.Keys._

object Deps {

  import Def.setting

  object Versions {
    def ammonium = "0.8.3-1"
    def flink = "1.1.3"
    def jupyterKernel = "0.4.1"
    def scio = "0.6.1"
    def beam = "2.6.0"
  }

  def ammonium = ("org.jupyter-scala" % "ammonite" % Versions.ammonium).cross(CrossVersion.full)
  def ammoniumCompiler = ("org.jupyter-scala" % "ammonite-compiler" % Versions.ammonium).cross(CrossVersion.full)
  def ammoniumRuntime = ("org.jupyter-scala" % "ammonite-runtime" % Versions.ammonium).cross(CrossVersion.full)
  def asm = "org.ow2.asm" % "asm-all" % "5.0.4"
  def caseApp = "com.github.alexarchambault" %% "case-app" % "1.1.3"
  def coursierCli = "io.get-coursier" %% "coursier-cli" % "1.0.0-RC2"
  def flinkClients = "org.apache.flink" %% "flink-clients" % Versions.flink
  def flinkRuntime = "org.apache.flink" %% "flink-runtime" % Versions.flink
  def flinkScala = "org.apache.flink" %% "flink-scala" % Versions.flink
  def flinkYarn = "org.apache.flink" %% "flink-yarn" % Versions.flink
  def jettyServer = "org.eclipse.jetty" % "jetty-server" % "8.1.14.v20131031"
  def jline = setting("jline" % "jline" % scalaBinaryVersion.value)
  def kantanCsv = "com.nrinaudo" %% "kantan.csv" % "0.1.12"
  def kernelApi = "org.jupyter-scala" %% "kernel-api" % Versions.jupyterKernel
  def kernel = "org.jupyter-scala" %% "kernel" % Versions.jupyterKernel
  def logback = "ch.qos.logback" % "logback-classic" % "1.2.1"
  def macroParadise = "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
  def pprint = "com.lihaoyi" %% "pprint" % "0.4.4"
  def scalaCompiler = setting("org.scala-lang" % "scala-compiler" % scalaVersion.value)
  def scalaReflect = setting("org.scala-lang" % "scala-reflect" % scalaVersion.value)
  def scalaXml = "org.scala-lang.modules" %% "scala-xml" % "1.0.6"
  def scioCore = "com.spotify" %% "scio-core" % Versions.scio
  def scioExtra = "com.spotify" %% "scio-extra" % Versions.scio
  def dataflowRunner = "org.apache.beam" % "beam-runners-google-cloud-dataflow-java" % Versions.beam
  def slf4jSimple = "org.slf4j" % "slf4j-simple" % "1.7.24"
  def sparkSql1 = "org.apache.spark" %% "spark-sql" % "1.3.1"
  def sparkSql = "org.apache.spark" %% "spark-sql" % "2.0.2"

}
