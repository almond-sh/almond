
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
  )
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
      Deps.collectionCompat,
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
      Deps.collectionCompat,
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
    mimaBinaryIssueFilters ++= Mima.scalaKernelApiRules,
    mimaPreviousArtifacts := {
      val sv = scalaVersion.value
      val previous = mimaPreviousArtifacts.value
      if (sv == "2.12.11" || sv == "2.13.2")
        previous.filter(mod => mod.revision != "0.9.0" && mod.revision != "0.9.1")
      else if (sv == "2.13.3")
        previous.filter(mod => !mod.revision.startsWith("0.9.") && mod.revision != "0.10.0")
      else if (sv == "2.13.4")
        previous.filter(mod => !mod.revision.startsWith("0.9.") && (!mod.revision.startsWith("0.10.") || mod.revision.drop("0.10.".length).length > 1))
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
    generatePropertyFile(
      "almond/almond.properties",
      extraProperties = Seq(
        "default-scalafmt-version" -> Deps.Versions.scalafmt
      )
    ),
    generateDependenciesFile,
    libraryDependencies ++= Seq(
      Deps.ammoniteCompiler,
      Deps.ammoniteReplApi,
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
    unmanagedSourceDirectories.in(Compile) += {
      val dirName = "scala-has-metabrowse"
      baseDirectory.value / "src" / "main" / dirName
    },
    libraryDependencies ++= Seq(
      Deps.coursier,
      Deps.coursierApi,
      Deps.directories,
      Deps.jansi,
      Deps.ammoniteCompiler,
      Deps.ammoniteRepl,
      Deps.mtags
    ),
    crossVersion := CrossVersion.full,
    testSettings
  )

lazy val `scala-kernel` = project
  .underScala
  .disablePlugins(MimaPlugin)
  .dependsOn(kernel, `scala-interpreter`, `scala-interpreter` % "test->test")
  .settings(
    shared,
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      Deps.caseApp,
      Deps.scalafmtDynamic
    ),
    utest
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
      Deps.ammoniteReplApi % "provided",
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
