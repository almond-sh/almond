import language.implicitConversions
import sbt._, Keys._
import sbtrelease.ReleasePlugin._
import com.typesafe.sbt.pgp.PgpKeys

object JupyterScalaBuild extends Build {
  private val publishSettings = xerial.sbt.Sonatype.sonatypeSettings ++ com.atlassian.labs.gitstamp.GitStampPlugin.gitStampSettings ++ Seq(
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
    crossVersion := CrossVersion.full,
    crossScalaVersions := Seq("2.10.3", "2.10.4", "2.10.5", "2.11.0", "2.11.1", "2.11.2", "2.11.4", "2.11.5", "2.11.6"),
    ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature"),
    resolvers ++= Seq(
      "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
      "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
      Resolver.sonatypeRepo("releases"),
      Resolver.sonatypeRepo("snapshots")
    ),
    scalacOptions += "-target:jvm-1.7"
  ) ++ publishSettings

  lazy val kernel = Project(id = "kernel", base = file("kernel"))
    .settings(commonSettings: _*)
    .settings(
      name := "jupyter-scala",
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-compiler" % scalaVersion.value,
        "com.github.alexarchambault.tmp" %% "ammonite-repl" % "0.2.7-SNAPSHOT" cross CrossVersion.full,
        "com.github.alexarchambault.jupyter" %% "jupyter-kernel" % version.value
      ),
      unmanagedSourceDirectories in Compile += (sourceDirectory in Compile).value / s"scala-${scalaBinaryVersion.value}"
    )

  lazy val cli = Project(id = "cli", base = file("cli"))
    .settings(commonSettings: _*)
    .settings(conscript.Harness.conscriptSettings: _*)
    .settings(xerial.sbt.Pack.packSettings ++ xerial.sbt.Pack.publishPackArchive: _*)
    .settings(
      name := "jupyter-scala-cli",
      libraryDependencies ++= Seq(
        "com.github.alexarchambault" %% "case-app" % "0.2.1",
        "ch.qos.logback" % "logback-classic" % "1.0.13"
      ),
      libraryDependencies <++= Def.setting {
        if (scalaVersion.value startsWith "2.10.")
          Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full))
        else
          Seq()
      },
      // Should not be necessary with the next release of sbt-pack (> 0.6.5)
      xerial.sbt.Pack.packMain := Map(
        "jupyter-scala" -> "jupyter.scala.JupyterScala",
        "jupyter-scala-embedded" -> "jupyter.scala.JupyterScalaEmbedded"
      )
    )
    .dependsOn(kernel)

  lazy val root = Project(id = "jupyter-scala", base = file("."))
    .settings(commonSettings: _*)
    .aggregate(kernel, cli)
}
