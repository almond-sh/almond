
val ammoniumVersion = "0.4.0-M5"
val jupyterKernelVersion = "0.3.0-M3"

lazy val `scala-api` = project.in(file("api"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.alexarchambault.ammonium" % "interpreter-api" % ammoniumVersion cross CrossVersion.full,
      "com.github.alexarchambault.jupyter" %% "kernel-api" % jupyterKernelVersion,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "com.github.alexarchambault.ammonium" % "tprint" % ammoniumVersion cross CrossVersion.full,
      "com.lihaoyi" %% "pprint" % "0.3.8"
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
  .settings(buildInfoSettings)
  .settings(
    sourceGenerators in Compile <+= buildInfo,
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      "ammoniumVersion" -> ammoniumVersion
    ),
    buildInfoPackage := "jupyter.scala"
  )

lazy val `scala-kernel` = project.in(file("kernel"))
  .dependsOn(`scala-api`)
  .settings(commonSettings)
  .settings(testSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.alexarchambault.jupyter" %% "kernel" % jupyterKernelVersion,
      "com.github.alexarchambault.ammonium" % "interpreter" % ammoniumVersion cross CrossVersion.full,
      "com.github.alexarchambault.ammonium" % "shell-tests" % ammoniumVersion % "test" cross CrossVersion.full
    ),
    libraryDependencies ++= Seq(
      "com.github.alexarchambault.jupyter" %% "kernel" % jupyterKernelVersion
    ).map(_ % "test" classifier "tests"),
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.10")
        Seq("org.scalamacros" % "paradise" % "2.0.1" % "plugin->default(compile)" cross CrossVersion.full)
      else
        Seq()
    }
  )

lazy val  `scala-cli` = project.in(file("cli"))
  .dependsOn(`scala-kernel`)
  .settings(commonSettings)
  .settings(packAutoSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.alexarchambault" %% "case-app" % "0.2.2",
      "ch.qos.logback" % "logback-classic" % "1.0.13"
    ),
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.10")
        Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full))
      else
        Seq()
    }
  )

lazy val `jupyter-scala` = project.in(file("."))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(`scala-api`, `scala-kernel`, `scala-cli`)
  .dependsOn(`scala-api`, `scala-kernel`, `scala-cli`)


lazy val commonSettings = Seq(
  organization := "com.github.alexarchambault.jupyter",
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature"),
  resolvers ++= Seq(
    "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
    Resolver.sonatypeRepo("releases")
  ),
  scalacOptions += "-target:jvm-1.7",
  crossVersion := CrossVersion.full,
  scalaVersion := "2.11.8",
  ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
  fork in test := true,
  fork in (Test, test) := true,
  fork in (Test, testOnly) := true,
  javaOptions in Test ++= Seq(
    "-Xmx3172M",
    "-Xms3172M"
  )
) ++ publishSettings

lazy val testSettings = Seq(
  libraryDependencies += "com.lihaoyi" %% "utest" % "0.3.0" % "test",
  testFrameworks += new TestFramework("utest.runner.Framework")
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

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)
