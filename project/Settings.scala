
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import com.typesafe.tools.mima.plugin.MimaPlugin
import sbt._
import sbt.Keys._

object Settings {

  def scala211 = "2.11.12"
  def scala212 = "2.12.8"
  def scala213 = "2.13.0"

  lazy val isAtLeast212 = Def.setting {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 12 => true
      case _ => false
    }
  }

  def onlyIn(sbv: String*) = {

    val sbv0 = sbv.toSet
    val ok = Def.setting {
      CrossVersion.partialVersion(scalaBinaryVersion.value)
        .map { case (maj, min) => s"$maj.$min" }
        .exists(sbv0)
    }

    Seq(
      baseDirectory := {
        val baseDir = baseDirectory.value

        if (ok.value)
          baseDir
        else
          baseDir / "target" / "dummy"
      },
      libraryDependencies := {
        val deps = libraryDependencies.value
        if (ok.value)
          deps
        else
          Nil
      },
      publishArtifact := ok.value
    )
  }

  lazy val shared = Seq(
    scalaVersion := scala213,
    crossScalaVersions := Seq(scala213, scala212, "2.12.7", "2.12.6", scala211),
    scalacOptions ++= Seq(
      // see http://tpolecat.github.io/2017/04/25/scalac-flags.html
      "-deprecation",
      "-feature",
      "-explaintypes",
      "-encoding", "utf-8",
      "-language:higherKinds",
      "-unchecked"
    ),
    resolvers ++= Seq(
      Resolver.sonatypeRepo("releases"),
      "jitpack" at "https://jitpack.io"
    ),
    // Seems required when cross-publishing for several scala versions
    // with same major and minor numbers (e.g. 2.12.6 and 2.12.7)
    publishConfiguration := publishConfiguration.value.withOverwrite(true),
    exportVersionsSetting,
    unmanagedSourceDirectories.in(Compile) ++= {
      val sbv = scalaBinaryVersion.value
      CrossVersion.partialVersion(sbv) match {
        case Some((2, 11)) =>
          Seq(baseDirectory.value / "src" / "main" / "scala-2.11_2.12")
        case Some((2, 12)) =>
          Seq(
            baseDirectory.value / "src" / "main" / "scala-2.11_2.12",
            baseDirectory.value / "src" / "main" / "scala-2.12_2.13"
          )
        case Some((2, 13)) =>
          Seq(baseDirectory.value / "src" / "main" / "scala-2.12_2.13")
        case _ => Nil
      }
    }
  ) ++ {
    val prop = sys.props.getOrElse("publish.javadoc", "").toLowerCase(java.util.Locale.ROOT)
    if (prop == "0" || prop == "false")
      Seq(
        sources in (Compile, doc) := Seq.empty,
        publishArtifact in (Compile, packageDoc) := false
      )
    else
      Nil
  }

  lazy val dontPublish = Seq(
    publish := {},
    publishLocal := {},
    publishArtifact := false
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
      val res = coursier.sbtcoursier.CoursierPlugin.autoImport.coursierResolutions
        .value
        .collectFirst {
          case (scopes, r) if scopes(coursier.core.Configuration.compile) =>
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
    libraryDependencies += Deps.utest.value % "test"
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

  lazy val exportVersions = taskKey[String]("Prints the current version to a dedicated file under target/")

  lazy val exportVersionsSetting: Setting[_] = {
    exportVersions := {
      val ver = version.value
      val ammoniteVer = Deps.Versions.ammonite.value
      val scalaVer = scalaVersion.value
      val outputDir = target.value

      outputDir.mkdirs()

      val output = outputDir / "version"
      Files.write(output.toPath, ver.getBytes(UTF_8))
      state.value.log.info(s"Wrote $output")

      val ammoniteOutput = outputDir / "ammonite-version"
      Files.write(ammoniteOutput.toPath, ammoniteVer.getBytes(UTF_8))
      state.value.log.info(s"Wrote $ammoniteOutput")

      val scalaOutput = outputDir / "scala-version"
      Files.write(scalaOutput.toPath, scalaVer.getBytes(UTF_8))
      state.value.log.info(s"Wrote $scalaOutput")

      ver
    }
  }

  lazy val mima = Seq(
    MimaPlugin.autoImport.mimaPreviousArtifacts := {
      val sv = scalaVersion.value
      val contains =
        if (sv.startsWith("2.13.")) "4e9441b9"
        else "v0.3.1"

      Mima.binaryCompatibilityVersions(contains).map { ver =>
        (organization.value % moduleName.value % ver).cross(crossVersion.value)
      }
    }
  )

}
