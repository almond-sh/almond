
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
  .settings(
    shared,
    testSettings,
    libraryDependencies += Deps.scalaReflect.value
  )

lazy val channels = project
  .underShared
  .dependsOn(logger)
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
  .settings(
    shared,
    libraryDependencies += Deps.argonautShapeless
  )

lazy val `interpreter-api` = project
  .underShared
  .settings(
    shared
  )

lazy val interpreter = project
  .underShared
  .dependsOn(`interpreter-api`, protocol)
  .settings(
    shared,
    testSettings
  )

lazy val kernel = project
  .underShared
  .dependsOn(interpreter, interpreter % "test->test")
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
  .settings(
    shared
  )

lazy val `scala-kernel-api` = project
  .underScala
  .dependsOn(`interpreter-api`)
  .settings(
    shared,
    crossVersion := CrossVersion.full,
    generatePropertyFile("almond/almond.properties"),
    generateDependenciesFile,
    libraryDependencies += Deps.ammoniteRepl
  )

lazy val `scala-interpreter` = project
  .underScala
  .dependsOn(interpreter, `scala-kernel-api`, kernel % "test->test")
  .settings(
    shared,
    crossVersion := CrossVersion.full,
    testSettings
  )

lazy val `scala-kernel` = project
  .underScala
  .dependsOn(kernel, `scala-interpreter`)
  .settings(
    shared,
    crossVersion := CrossVersion.full,
    libraryDependencies += Deps.caseApp
  )

lazy val echo = project
  .underModules
  .dependsOn(kernel, test % Test)
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
    libraryDependencies ++= Seq(
      Deps.ammoniteRepl % "provided",
      Deps.ammoniteSpark,
      Deps.argonautShapeless,
      Deps.sparkSql % "provided"
    ),
    disableScalaVersion("2.12")
  )

lazy val almond = project
  .in(file("."))
  .aggregate(
    `almond-spark`,
    channels,
    echo,
    `interpreter-api`,
    interpreter,
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
