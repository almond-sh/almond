import language.implicitConversions
import sbt._, Keys._
import sbtbuildinfo.Plugin._
import sbtrelease.ReleasePlugin._
import com.typesafe.sbt.pgp.PgpKeys
import xerial.sbt.Pack._

import scala.util.Try

object JupyterScalaBuild extends Build {
  private val publishSettings = com.atlassian.labs.gitstamp.GitStampPlugin.gitStampSettings ++ Seq(
    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },
    licenses := Seq("Apache License" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    scmInfo := Some(ScmInfo(url("https://github.com/alexarchambault/jupyter-scala"), "git@github.com:alexarchambault/jupyter-scala.git")),
    pomExtra := {
      <url>https://github.com/alexarchambault/jupyter-scala</url>
      <developers>
        <developer>
          <id>alexarchambault</id>
          <name>Alexandre Archambault</name>
          <url>https://github.com/alexarchambault</url>
        </developer>
      </developers>
    },
    credentials += {
      Seq("SONATYPE_USER", "SONATYPE_PASS").map(sys.env.get) match {
        case Seq(Some(user), Some(pass)) =>
          Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
        case _ =>
          Credentials(Path.userHome / ".ivy2" / ".credentials")
      }
    },
    ReleaseKeys.versionBump := sbtrelease.Version.Bump.Bugfix,
    ReleaseKeys.publishArtifactsAction := PgpKeys.publishSigned.value
  )

  private val commonSettings = Seq(
    organization := "com.github.alexarchambault.jupyter",
    scalaVersion := "2.11.6",
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature"),
    resolvers ++= Seq(
      "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
      Resolver.sonatypeRepo("releases"),
      Resolver.sonatypeRepo("snapshots")
    ),
    scalacOptions += "-target:jvm-1.7",
    crossVersion := CrossVersion.full,
    crossScalaVersions := Seq("2.10.3", "2.10.4", "2.10.5", "2.11.0", "2.11.1", "2.11.2", "2.11.4", "2.11.5", "2.11.6"),
    ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
    fork in test := true,
    fork in (Test, test) := true,
    fork in (Test, testOnly) := true
  ) ++ publishSettings

  private lazy val testSettings = Seq(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "utest" % "0.3.0" % "test"
    ),
    testFrameworks += new TestFramework("utest.runner.Framework")
  )

  private val ammoniteVersion = "0.3.1-SNAPSHOT"
  private val jupyterKernelVersion = "0.2.0-SNAPSHOT"

  def computeModuleDeps(report: UpdateReport,
                        scalaVersion: String,
                        scalaBinaryVersion: String,
                        modules: (String, String, String)*): String = {

    def get(m: ModuleID) = (m.organization, m.name, m.revision)

    def normalize(m: ModuleID): ModuleID =
      m.crossVersion match {
        case f: CrossVersion.Full =>
          m.copy(name = m.name + "_" + scalaVersion, crossVersion = CrossVersion.Disabled)
        case b: CrossVersion.Binary =>
          m.copy(name = m.name + "_" + scalaBinaryVersion, crossVersion = CrossVersion.Disabled)
        case _: CrossVersion.Disabled.type =>
          m
      }

    val configReport = report.configuration("compile").get
    val repModules = configReport.modules
    val m = repModules.flatMap(m => m.callers.map(c => get(normalize(c.caller)) -> get(normalize(m.module)))).groupBy(_._1).mapValues(_.map(_._2))

    val deps = modules ++ modules.flatMap(module => m.getOrElse(module, Nil))

    deps.sorted.distinct.map{ case (org, name, rev) => s"$org:$name:$rev" }.mkString(",")
  }

  val apiDeps = TaskKey[String]("apiDeps")
  val compilerDeps = TaskKey[String]("compilerDeps")

  lazy val api = Project(id = "api", base = file("api"))
    .settings(commonSettings: _*)
    .settings(
      name := "jupyter-scala-api",
      libraryDependencies ++= Seq(
        "com.github.alexarchambault" % "ammonite-api" % ammoniteVersion cross CrossVersion.full,
        "com.github.alexarchambault.jupyter" %% "jupyter-api" % jupyterKernelVersion,
        "org.scala-lang" % "scala-reflect" % scalaVersion.value,
        "com.lihaoyi" %% "ammonite-pprint" % "0.3.0"
      ),
      libraryDependencies ++= {
        if (scalaVersion.value startsWith "2.10.")
          Seq()
        else
          Seq(
            "org.scala-lang.modules" %% "scala-xml" % "1.0.3"
          )
      }
    )
    .settings(buildInfoSettings: _*)
    .settings(
      sourceGenerators in Compile <+= buildInfo,
      buildInfoKeys := Seq[BuildInfoKey](
        version,
        "ammoniteVersion" -> ammoniteVersion
      ),
      buildInfoPackage := "jupyter.scala"
    )

  lazy val kernel = Project(id = "kernel", base = file("kernel"))
    .dependsOn(api)
    .settings(commonSettings ++ testSettings: _*)
    .settings(
      name := "jupyter-scala",
      libraryDependencies ++= Seq(
        "com.github.alexarchambault.jupyter" %% "jupyter-kernel" % jupyterKernelVersion,
        "com.github.alexarchambault" %% "ammonite-interpreter" % ammoniteVersion cross CrossVersion.full,
        "com.github.alexarchambault" %% "ammonite-shell" % ammoniteVersion % "test" cross CrossVersion.full
      ),
      libraryDependencies ++= Seq(
        "com.github.alexarchambault.jupyter" %% "jupyter-kernel" % jupyterKernelVersion,
        "com.github.alexarchambault" %% "ammonite-shell" % ammoniteVersion cross CrossVersion.full
      ).map(_ % "test" classifier "tests"),
      libraryDependencies ++= {
        if (scalaVersion.value startsWith "2.10.")
          Seq("org.scalamacros" % "paradise" % "2.0.1" % "plugin->default(compile)" cross CrossVersion.full)
        else
          Seq()
      }
    )
    .settings(
      apiDeps := {
        computeModuleDeps(update.value, scalaVersion.value, scalaBinaryVersion.value,
          (organization.value, s"jupyter-scala-api_${scalaVersion.value}", version.value))
      },
      compilerDeps := {
        val modules = List(
          (organization.value, s"jupyter-scala-api_${scalaVersion.value}", version.value),
          ("org.scala-lang", "scala-compiler", scalaVersion.value)
        ) ++ {
          if (scalaVersion.value.startsWith("2.10.")) Seq(("org.scalamacros", s"paradise_${scalaVersion.value}", "2.0.1"))
          else Seq()
        }

        computeModuleDeps(update.value, scalaVersion.value, scalaBinaryVersion.value, modules: _*)
      }
    )
    .settings(buildInfoSettings: _*)
    .settings(
      sourceGenerators in Compile <+= buildInfo,
      buildInfoKeys := Seq[BuildInfoKey](
        apiDeps,
        compilerDeps
      ),
      buildInfoPackage := "jupyter.scala",
      buildInfoObject := "KernelBuildInfo"
    )

  lazy val cli = Project(id = "cli", base = file("cli"))
    .dependsOn(kernel)
    .settings(commonSettings: _*)
    .settings(packAutoSettings ++ publishPackTxzArchive ++ publishPackZipArchive: _*)
    .settings(
      // overriding these three settings so that the directory name in the published packages matches the package file names.
      // e.g. directory jupyter-scala_2.11.6-0.2.0 in package jupyter-scala_2.11.6-0.2.0.tar.xz
      packArchivePrefix := s"jupyter-scala_${scalaVersion.value}",
      packArchiveTxzArtifact := Artifact("jupyter-scala", "arch", "tar.xz"),
      packArchiveZipArtifact := Artifact("jupyter-scala", "arch", "zip")
    )
    .settings(
      name := "jupyter-scala-cli",
      libraryDependencies ++= Seq(
        "com.github.alexarchambault" %% "case-app" % "0.2.2",
        "ch.qos.logback" % "logback-classic" % "1.0.13"
      ),
      libraryDependencies ++= {
        if (scalaVersion.value startsWith "2.10.")
          Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full))
        else
          Seq()
      }
    )

  lazy val root = Project(id = "jupyter-scala", base = file("."))
    .settings(commonSettings: _*)
    .aggregate(api, kernel, cli)
}
