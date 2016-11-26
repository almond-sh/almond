
val ammoniumVersion = "0.8.1-SNAPSHOT"
val jupyterKernelVersion = "0.4.0-SNAPSHOT"

lazy val `scala-api` = project.in(file("api"))
  .settings(commonSettings)
  .settings(
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      "org.jupyter-scala" % "ammonite-runtime" % ammoniumVersion cross CrossVersion.full,
      "org.jupyter-scala" %% "kernel-api" % jupyterKernelVersion,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "com.lihaoyi" %% "pprint" % "0.4.2"
    ),
    libraryDependencies ++= {
      if (scalaVersion.value startsWith "2.10.")
        Seq()
      else
        Seq(
          "org.scala-lang.modules" %% "scala-xml" % "1.0.4"
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
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      "org.jupyter-scala" %% "kernel" % jupyterKernelVersion,
      "org.jupyter-scala" % "ammonite-compiler" % ammoniumVersion cross CrossVersion.full
    ),
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.10")
        Seq("org.scalamacros" % "paradise" % "2.1.0" % "plugin->default(compile)" cross CrossVersion.full)
      else
        Seq()
    }
  )

lazy val `scala-cli` = project.in(file("cli"))
  .dependsOn(`scala-kernel`)
  .settings(commonSettings)
  .settings(packAutoSettings)
  .settings(
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      "com.github.alexarchambault" %% "case-app" % "1.1.2",
      "ch.qos.logback" % "logback-classic" % "1.1.7"
    ),
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.10")
        Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full))
      else
        Seq()
    }
  )

lazy val spark = project
  .dependsOn(`scala-api`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % "1.6.2" % "provided",
      "org.eclipse.jetty" % "jetty-server" % "8.1.14.v20131031",
      "io.get-coursier" %% "coursier-cli" % "1.0.0-M14-7"
    )
  )

lazy val `jupyter-scala` = project
  .in(file("."))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(`scala-api`, `scala-kernel`, `scala-cli`, spark)


lazy val commonSettings = Seq(
  organization := "org.jupyter-scala",
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature"),
  resolvers ++= Seq(
    "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
    Resolver.sonatypeRepo("releases")
  ),
  scalacOptions += "-target:jvm-1.7",
  scalaVersion := "2.11.8",
  ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
  fork in test := true,
  fork in (Test, test) := true,
  fork in (Test, testOnly) := true,
  javaOptions in Test ++= Seq(
    "-Xmx3172M",
    "-Xms3172M"
  ),
  resolvers += Resolver.jcenterRepo
) ++ publishSettings

lazy val testSettings = Seq(
  libraryDependencies += "com.lihaoyi" %% "utest" % "0.4.4" % "test",
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
