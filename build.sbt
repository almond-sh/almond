
import Aliases._
import Settings._

lazy val api = project
  .settings(
    shared,
    scalaPrefix,
    crossVersion := CrossVersion.full,
    libs ++= Seq(
      Deps.ammoniumRuntime,
      Deps.kernelApi,
      Deps.scalaReflect.value,
      Deps.pprint
    ),
    scalaXmlIfNeeded,
    jupyterScalaBuildInfoSettingsIn("jupyter.scala")
  )

lazy val kernel = project
  .dependsOn(api)
  .settings(
    shared,
    scalaPrefix,
    testSettings,
    crossVersion := CrossVersion.full,
    libs ++= Seq(
      Deps.kernel,
      Deps.ammoniumCompiler
    )
  )

lazy val cli = project
  .dependsOn(kernel)
  .settings(
    shared,
    scalaPrefix,
    packAutoSettings,
    crossVersion := CrossVersion.full,
    libs ++= Seq(
      Deps.caseApp,
      Deps.logback
    )
  )

lazy val `spark-stubs-1` = project
  .in(file("spark/stubs-1.x"))
  .settings(
    shared,
    libs += Deps.sparkSql1 % "provided",
    disableScalaVersion("2.12")
  )

lazy val `spark-stubs-2` = project
  .in(file("spark/stubs-2.x"))
  .settings(
    shared,
    libs += Deps.sparkSql % "provided",
    disableScalaVersion("2.12")
  )

lazy val spark = project
  .in(file("spark/core"))
  .dependsOn(api % "provided")
  .settings(
    shared,
    libs ++= Seq(
      Deps.sparkSql % "provided",
      Deps.jettyServer,
      Deps.coursierCli
    ),
    disableScalaVersion("2.12"),
    jupyterScalaBuildInfoSettingsIn("jupyter.spark.internals")
  )

lazy val `spark-tests` = project
  .dependsOn(api)
  .in(file("spark/tests"))
  .settings(
    shared,
    dontPublish,
    testSettings,
    classpathTypes += "test-jar",
    libs ++= Seq(
      // FIXME Going hoops and loops to get that one (because of coursier?),
      // to be fine when pulling artifacts from both sonatype and ~/.ivy2/local
      Deps.ammonium,
      Deps.ammonium % "compile->test",
      Deps.ammonium classifier "tests"
    )
  )

lazy val flink = project
  .dependsOn(api % "provided")
  .settings(
    shared,
    libs ++= Seq(
      Deps.flinkRuntime,
      Deps.flinkClients,
      Deps.flinkScala,
      Deps.asm // don't know why we have to manually pull this one
    ),
    disableScalaVersion("2.12")
  )

lazy val `flink-yarn` = project
  .dependsOn(flink, api % "provided")
  .settings(
    shared,
    libs ++= Seq(
      Deps.coursierCli,
      Deps.flinkYarn
    ),
    disableScalaVersion("2.12")
  )

lazy val scio = project
  .dependsOn(api % "provided")
  .settings(
    shared,
    libs ++= {
      Seq(
        Deps.slf4jSimple,
        Deps.jline.value,
        Deps.scalaCompiler.value,
        Deps.scalaReflect.value,
        Deps.kantanCsv,
        Deps.macroParadise,
        Deps.scioCore,
        Deps.scioExtra,
        Deps.dataflowRunner
      )
    },
    disableScalaVersion("2.12")
  )


lazy val `jupyter-scala` = project
  .in(root)
  .aggregate(
    api,
    kernel,
    cli,
    `spark-stubs-1`,
    `spark-stubs-2`,
    spark,
    `spark-tests`,
    flink,
    `flink-yarn`,
    scio
  )
  .settings(
    shared,
    dontPublish
  )
