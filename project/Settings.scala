import sbt._
import sbt.Keys._
import Aliases._
import sbtbuildinfo.Plugin._

object Settings {

  lazy val shared = Seq(
    organization := "org.jupyter-scala",
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature"),
    resolvers ++= Seq(
      "Scalaz Bintray Repo" at "https://dl.bintray.com/scalaz/releases",
      Resolver.sonatypeRepo("releases"),
      Resolver.sonatypeRepo("snapshots")
    ),
    scalacOptions ++= {
      if (scalaBinaryVersion.value == "2.12")
        Seq()
      else
        Seq("-target:jvm-1.7")
    },
    ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
    resolvers += Resolver.jcenterRepo,
    libs ++= {
      scalaBinaryVersion.value match {
        case "2.10" =>
          Seq(compilerPlugin(Deps.macroParadise))
        case _ => Nil
      }
    }
  ) ++ publishSettings

  val testJavaOptions = Seq(
    "-Xmx3172M",
    "-Xms3172M"
  )

  lazy val testSettings = Seq(
    libs += "com.lihaoyi" %% "utest" % "0.4.4" % "test",
    testFrameworks += new TestFramework("utest.runner.Framework"),
    fork in test := true,
    fork in (Test, test) := true,
    fork in (Test, testOnly) := true,
    javaOptions in Test ++= testJavaOptions,
    javaOptions in (Test, test) ++= testJavaOptions,
    javaOptions in (Test, testOnly) ++= testJavaOptions
  )

  lazy val publishSettings = Seq(
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
    homepage := Some(url("https://github.com/alexarchambault/jupyter-scala")),
    pomExtra := {
      <developers>
        <developer>
          <id>alexarchambault</id>
          <name>Alexandre Archambault</name>
          <url>https://github.com/alexarchambault</url>
        </developer>
      </developers>
    },
    credentials ++= {
      for (user <- sys.env.get("SONATYPE_USER"); pass <- sys.env.get("SONATYPE_PASS"))
        yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
    }.toSeq
  )

  lazy val dontPublish = Seq(
    publish := (),
    publishLocal := (),
    publishArtifact := false
  )

  def disableScalaVersion(sbv: String*) = Seq(
    baseDirectory := {
      if (sbv.contains(scalaBinaryVersion.value))
        baseDirectory.value / "dummy"
      else
        baseDirectory.value
    },
    libs := {
      if (sbv.contains(scalaBinaryVersion.value))
        Nil
      else
        libs.value
    },
    publish := {
      if (!sbv.contains(scalaBinaryVersion.value))
        publish.value
    },
    publishLocal := {
      if (!sbv.contains(scalaBinaryVersion.value))
        publishLocal.value
    },
    publishArtifact := {
      !sbv.contains(scalaBinaryVersion.value) && publishArtifact.value
    }
  )

  def jupyterScalaBuildInfoSettingsIn(packageName: String) = buildInfoSettings ++ Seq(
    sourceGenerators in Compile += buildInfo.taskValue,
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      "ammoniumVersion" -> Deps.Versions.ammonium
    ),
    buildInfoPackage := packageName
  )

  lazy val scalaPrefix = {
    name := "scala-" + name.value
  }

  lazy val scalaXmlIfNeeded = {
    libs ++= {
      scalaBinaryVersion.value match {
        case "2.10" => Nil
        case _ =>
          Seq(Deps.scalaXml)
      }
    }
  }

}
