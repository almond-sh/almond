
import Settings._

inThisBuild(List(
  organization := "sh.almond",
  homepage := Some(url("https://github.com/almond-sh/almond")),
  licenses := List("BSD-3-Clause" -> url("https://opensource.org/licenses/BSD-3-Clause")),
  developers := List(
    Developer(
      "alexarchambault",
      "Alexandre Archambault",
      "alexandre.archambault@gmail.com",
      url("https://github.com/alexarchambault")
    )
  ),
  version := {
    // Simple X.Y.Z-SNAPSHOT versions are easier to find once published locally
    val forceSimpleVersion = sys.env
      .get("FORCE_SIMPLE_VERSION")
      .contains("1")
    val onTravisCi = sys.env.exists(_._1.startsWith("TRAVIS_"))
    val v = version.value
    if ((forceSimpleVersion || !onTravisCi) && v.contains("+") && v.endsWith("-SNAPSHOT")) {
      val base = v.takeWhile(_ != '+')
      val elems = base.split('.')
      val last = scala.util.Try(elems.last.toInt)
        .toOption
	.fold(elems.last)(n => (n + 1).toString)
      val bumpedBase = (elems.init :+ last).mkString(".")
      bumpedBase + "-SNAPSHOT"
    } else
      v
  }
))

lazy val logger = project
  .underShared
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    testSettings,
    libraryDependencies += Deps.scalaReflect.value
  )

lazy val channels = project
  .underShared
  .dependsOn(logger)
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    testSettings,
    libraryDependencies ++= Seq(
      Deps.fs2,
      Deps.jeromq
    )
  )

lazy val protocol = project
  .underShared
  .dependsOn(channels)
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    testSettings,
    libraryDependencies ++= Seq(
      Deps.jsoniterScalaCore,
      Deps.jsoniterScalaMacros % Provided
    )
  )

lazy val `interpreter-api` = project
  .underShared
  .settings(
    shared,
    mima
  )

lazy val interpreter = project
  .underShared
  .dependsOn(`interpreter-api`, protocol)
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    libraryDependencies ++= Seq(
      Deps.scalatags,
      // picked by jboss-logging, that metabrowse transitively depends on
      Deps.slf4jNop
    ),
    testSettings
  )

lazy val kernel = project
  .underShared
  .dependsOn(interpreter, interpreter % "test->test")
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    testSettings,
    libraryDependencies ++= Seq(
      Deps.caseAppAnnotations,
      Deps.fs2
    )
  )

lazy val test = project
  .underShared
  .dependsOn(`interpreter-api`)
  .disablePlugins(MimaPlugin)
  .settings(
    shared
  )

lazy val `jupyter-api` = project
  .underScala
  .dependsOn(`interpreter-api`)
  .settings(
    shared,
    mima,
    libraryDependencies += Deps.jvmRepr
  )

lazy val `scala-kernel-api` = project
  .underScala
  .dependsOn(`interpreter-api`, `jupyter-api`)
  .settings(
    shared,
    mima,
    mimaPreviousArtifacts := {
      val sv = scalaVersion.value
      val previous = mimaPreviousArtifacts.value
      if (sv == "2.12.11" || sv == "2.13.2")
        previous.filter(mod => mod.revision != "0.9.0" && mod.revision != "0.9.1")
      else if (sv == "2.13.3")
        previous.filter(mod => !mod.revision.startsWith("0.9.") && mod.revision != "0.10.0")
      else if (sv == "2.12.12")
        previous.filter { mod =>
          !mod.revision.startsWith("0.9.") &&
            mod.revision != "0.10.0" &&
            mod.revision != "0.10.1" &&
            mod.revision != "0.10.2"
        }
      else
        previous
    },
    crossVersion := CrossVersion.full,
    generatePropertyFile("almond/almond.properties"),
    generateDependenciesFile,
    libraryDependencies ++= Seq(
      Deps.ammoniteReplApi.value,
      Deps.jvmRepr
    )
  )

lazy val addMetabrowse = Def.setting {
  val sv = scalaVersion.value
  val patch = sv
    .split('.')
    .drop(2)
    .headOption
    .flatMap(s => scala.util.Try(s.takeWhile(_.isDigit).toInt).toOption)
  (sv.startsWith("2.12.") && patch.exists(_ <= 10)) ||
    (sv.startsWith("2.13.") && patch.exists(_ <= 1))
}

lazy val `scala-interpreter` = project
  .underScala
  .dependsOn(interpreter, `scala-kernel-api`, kernel % "test->test", `almond-rx` % Test)
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    libraryDependencies ++= {
      if (addMetabrowse.value)
        Seq(Deps.metabrowseServer)
      else
        Nil
    },
    unmanagedSourceDirectories.in(Compile) += {
      val dirName =
        if (addMetabrowse.value) "scala-has-metabrowse"
        else "scala-no-metabrowse"
      baseDirectory.value / "src" / "main" / dirName
    },
    libraryDependencies ++= Seq(
      Deps.coursier,
      Deps.coursierApi,
      Deps.directories,
      Deps.jansi,
      Deps.ammoniteRepl.value
    ),
    crossVersion := CrossVersion.full,
    testSettings
  )

lazy val `scala-kernel` = project
  .underScala
  .enablePlugins(PackPlugin)
  .disablePlugins(MimaPlugin)
  .dependsOn(kernel, `scala-interpreter`)
  .settings(
    shared,
    crossVersion := CrossVersion.full,
    libraryDependencies += Deps.caseApp,
    packExcludeArtifactTypes -= "source",
    packModuleEntries ++= {
      val report = updateClassifiers.value
      for {
        c <- report.configurations
        m <- c.modules
        (a, f) <- m.artifacts
        if a.classifier.contains("sources")
      } yield xerial.sbt.pack.PackPlugin.ModuleEntry(
        m.module.organization,
        m.module.name,
        xerial.sbt.pack.VersionString(m.module.revision),
        a.name,
        a.classifier,
        f
      )
    }
  )

lazy val echo = project
  .underModules
  .dependsOn(kernel, test % Test)
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    generatePropertyFile("almond/echo.properties"),
    testSettings,
    libraryDependencies += Deps.caseApp
  )

lazy val `almond-spark` = project
  .underScala
  .dependsOn(`scala-kernel-api` % "provided")
  .settings(
    shared,
    mimaExceptIn("2.13"),
    libraryDependencies ++= Seq(
      Deps.ammoniteReplApi.value % "provided",
      Deps.ammoniteSpark,
      Deps.jsoniterScalaCore,
      Deps.jsoniterScalaMacros % Provided,
      Deps.sparkSql % "provided"
    ),
    onlyIn("2.12"),
    sources.in(Compile, doc) := Nil
  )

lazy val `almond-rx` = project
  .underScala
  .dependsOn(`scala-kernel-api` % Provided)
  .settings(
    shared,
    mimaExceptIn("2.13"),
    libraryDependencies += Deps.scalaRx,
    onlyIn("2.12")
  )

lazy val almond = project
  .in(file("."))
  .disablePlugins(MimaPlugin)
  .aggregate(
    `almond-rx`,
    `almond-spark`,
    channels,
    echo,
    `interpreter-api`,
    interpreter,
    `jupyter-api`,
    kernel,
    logger,
    protocol,
    `scala-interpreter`,
    `scala-kernel-api`,
    `scala-kernel`,
    test
  )
  .settings(
    shared,
    dontPublish
  )

lazy val jupyterStart = taskKey[Unit]("")
lazy val writeDebugKernelJson = taskKey[Unit]("")
lazy val jupyterStop = taskKey[Unit]("")
lazy val jupyterDir = taskKey[File]("")

jupyterDir := {
  baseDirectory.in(ThisBuild).value / "target" / "jupyter"
}

lazy val jupyterCommand = Seq("jupyter", "lab")

writeDebugKernelJson := {
  val pack0 = (pack.in(`scala-kernel`).value / "bin" / "scala-kernel").getAbsolutePath
  val jupyterDir0 = jupyterDir.value
  val dir = jupyterDir0 / "kernels" / "scala-debug"
  dir.mkdirs()
  val kernelJson = s"""{
    "language": "scala",
    "display_name": "Scala (sources)",
    "argv": [
      "$pack0",
      "--log", "info",
      "--connection-file", "{connection_file}"
    ]
  }"""
  java.nio.file.Files.write((dir / "kernel.json").toPath, kernelJson.getBytes("UTF-8"))
  streams.value.log.info(s"JUPYTER_PATH=$jupyterDir0")
}

jupyterStart := {
  writeDebugKernelJson.value
  val jupyterDir0 = jupyterDir.value

  val b = new ProcessBuilder(jupyterCommand: _*).inheritIO()
  val env = b.environment()
  env.put("JUPYTER_PATH", jupyterDir0.getAbsolutePath)
  val p = b.start()
  val pidOpt = try {
    val fld = p.getClass.getDeclaredField("pid")
    fld.setAccessible(true)
    Some(fld.getInt(p))
  } catch {
    case _: Throwable => None
  }
  for (pid <- pidOpt) {
    java.nio.file.Files.write((jupyterDir0 / "pid").toPath, pid.toString.getBytes("UTF-8"))
    java.lang.Runtime.getRuntime.addShutdownHook(
      new Thread("jupyter-stop") {
        override def run() =
          Helper.jupyterStop(jupyterDir0)
      }
    )
  }
}

lazy val Helper = new {
  def jupyterStop(jupyterDir: File): Unit = {
    val pidFile = jupyterDir / "pid"
    if (pidFile.exists()) {
      val b = java.nio.file.Files.readAllBytes((jupyterDir / "pid").toPath)
      val pid = new String(b, "UTF-8").toInt
      new ProcessBuilder("kill", pid.toString).start().waitFor()
      java.nio.file.Files.deleteIfExists(pidFile.toPath)
    }
  }
}

jupyterStop := {
  val jupyterDir0 = jupyterDir.value
  Helper.jupyterStop(jupyterDir0)
}
