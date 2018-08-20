
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import sbt._
import sbt.Keys._

object Settings {

  private val scala211 = "2.11.12"
  private val scala212 = "2.12.6"

  lazy val shared = Seq(
    scalaVersion := scala212,
    crossScalaVersions := Seq(scala212, scala211),
    scalacOptions ++= Seq(
      // see http://tpolecat.github.io/2017/04/25/scalac-flags.html
      "-deprecation",
      "-feature",
      "-explaintypes",
      "-encoding", "utf-8",
      "-language:higherKinds",
      "-unchecked"
    ),
    resolvers += Resolver.sonatypeRepo("releases")
  )

  lazy val dontPublish = Seq(
    publish := (),
    publishLocal := (),
    publishArtifact := false
  )

  def disableScalaVersion(sbv: String*) = Seq(
    baseDirectory := {
      if (sbv.contains(scalaBinaryVersion.value))
        baseDirectory.value / "target" / "dummy"
      else
        baseDirectory.value
    },
    libraryDependencies := {
      if (sbv.contains(scalaBinaryVersion.value))
        Nil
      else
        libraryDependencies.value
    },
    publishArtifact := {
      !sbv.contains(scalaBinaryVersion.value) && publishArtifact.value
    }
  )

  def generatePropertyFile(path: String) =
    resourceGenerators.in(Compile) += Def.task {
      import sys.process._

      val dir = classDirectory.in(Compile).value
      val ver = version.value

      val f = path.split('/').foldLeft(dir)(_ / _)
      f.getParentFile.mkdirs()

      val p = new java.util.Properties

      p.setProperty("version", ver)
      p.setProperty("commit-hash", Seq("git", "rev-parse", "HEAD").!!.trim)

      val w = new java.io.FileOutputStream(f)
      p.store(w, "Almond properties")
      w.close()

      state.value.log.info(s"Wrote $f")

      Seq(f)
    }

  lazy val generateDependenciesFile =
    resourceGenerators.in(Compile) += Def.task {

      val dir = classDirectory.in(Compile).value / "almond"
      val res = coursier.CoursierPlugin.autoImport.coursierResolutions
        .value
        .collectFirst {
          case (scopes, r) if scopes("compile") =>
            r
        }
        .getOrElse(
          sys.error("compile coursier resolution not found")
        )

      val content = res
        .minDependencies
        .toVector
        .map { d =>
          (d.module.organization, d.module.name, d.version)
        }
        .sorted
        .map {
          case (org, name, ver) =>
          s"$org:$name:$ver"
        }
        .mkString("\n")

      val f = dir / "almond-user-dependencies.txt"
      dir.mkdirs()

      Files.write(f.toPath, content.getBytes(UTF_8))

      state.value.log.info(s"Wrote $f")

      Seq(f)
    }

  lazy val testSettings = Seq(
    fork.in(Test) := true, // Java serialization goes awry without that
    testFrameworks += new TestFramework("utest.runner.Framework"),
    javaOptions.in(Test) ++= Seq("-Xmx3g", "-Dfoo=bzz"),
    libraryDependencies += Deps.utest % "test"
  )

  implicit class ProjectOps(val project: Project) extends AnyVal {
    def underModules: Project = {
      val base = project.base.getParentFile / "modules" / project.base.getName
      project.in(base)
    }
    def underScala: Project = {
      val base = project.base.getParentFile / "modules" / "scala" / project.base.getName
      project.in(base)
    }
    def underShared: Project = {
      val base = project.base.getParentFile / "modules" / "shared" / project.base.getName
      project.in(base)
    }
  }

}
