
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import com.typesafe.tools.mima.plugin.MimaPlugin
import sbt._
import sbt.Keys._

object Settings {

  def scala212 = "2.12.12"
  def scala213 = "2.13.3"

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
    crossScalaVersions := Seq(scala213, "2.13.2", "2.13.1", "2.13.0", scala212, "2.12.11", "2.12.10", "2.12.9", "2.12.8"),
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
          Nil
        case Some((2, 12)) =>
          Seq(baseDirectory.value / "src" / "main" / "scala-2.12_2.13")
        case Some((2, 13)) =>
          Seq(baseDirectory.value / "src" / "main" / "scala-2.12_2.13")
        case _ => Nil
      }
    },
    unmanagedSourceDirectories.in(Compile) ++= {
      val sv = scalaVersion.value
      if (sv.startsWith("2.12.")) {
        val patch = sv.stripPrefix("2.12.").takeWhile(_.isDigit).toInt
        val dirName =
          if (patch <= 8)
            "scala-2.12.0_8"
          else
            "scala-2.12.9+"
        Seq(baseDirectory.value / "src" / "main" / dirName)
      } else
        Nil
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
      val ammSparkVer = Deps.ammoniteSpark.revision

      val f = path.split('/').foldLeft(dir)(_ / _)
      f.getParentFile.mkdirs()

      val p = new java.util.Properties

      p.setProperty("version", ver)
      p.setProperty("commit-hash", Seq("git", "rev-parse", "HEAD").!!.trim)
      // FIXME Only set if ammonite-spark is available for the current scala version?
      p.setProperty("ammonite-spark-version", ammSparkVer)

      val w = new java.io.FileOutputStream(f)
      p.store(w, "Almond properties")
      w.close()

      state.value.log.info(s"Wrote $f")

      Seq(f)
    }

  lazy val generateDependenciesFile =
    resourceGenerators.in(Compile) += Def.task {

      val sv = scalaVersion.value
      val sbv = scalaBinaryVersion.value
      val updateReport = update.value
      val configReport = updateReport.configuration(Compile).getOrElse {
        sys.error("Compile report not found")
      }
      val deps = configReport
        .modules
        .filter(!_.evicted)
        .map(_.module)
      val projectDeps = projectDependencies
        .value
      val content = (deps ++ projectDeps)
        .map { mod =>
          val name = CrossVersion(mod.crossVersion, sv, sbv)
            .getOrElse(identity[String] _)
            .apply(mod.name)
          (mod.organization, name, mod.revision)
        }
        .distinct
        .sorted
        .map {
          case (org, name, ver) =>
          s"$org:$name:$ver"
        }
        .mkString("\n")
      val dir = classDirectory.in(Compile).value / "almond"

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

  lazy val exportVersions = taskKey[String]("Prints the current version to a dedicated file under target/")

  lazy val exportVersionsSetting: Setting[_] = {
    exportVersions := {
      val ver = version.value
      val ammoniteVer = Deps.Versions.ammonite
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

      Mima.binaryCompatibilityVersions().map { ver =>
        (organization.value % moduleName.value % ver).cross(crossVersion.value)
      }
    }
  )

  def mimaExceptIn(scalaVersions: String*) = Seq(
    MimaPlugin.autoImport.mimaPreviousArtifacts := {
      val sv = scalaVersion.value

      if (scalaVersions.exists(v => sv.startsWith(v + ".")))
        Set.empty
      else
        Mima.binaryCompatibilityVersions().map { ver =>
          (organization.value % moduleName.value % ver).cross(crossVersion.value)
        }
    }
  )

}
