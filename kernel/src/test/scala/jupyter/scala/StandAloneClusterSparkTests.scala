package jupyter.scala

import ammonite.shell.tests

class StandAloneClusterSparkTests(sparkVersion: (Int, Int)) extends tests.SparkTests(
  ScalaInterpreterChecker(), "spark://master:7077", sparkVersion, loadAmmoniteSpark = true)

object StandAloneClusterSpark12Tests extends StandAloneClusterSparkTests((1, 2))
object StandAloneClusterSpark13Tests extends StandAloneClusterSparkTests((1, 3))
