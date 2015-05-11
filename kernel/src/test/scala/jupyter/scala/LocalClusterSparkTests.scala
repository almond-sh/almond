package jupyter.scala

import ammonite.shell.tests

class LocalClusterSparkTests(sparkVersion: (Int, Int)) extends tests.SparkTests(
  ScalaInterpreterChecker(), "local-cluster[1,1,512]", sparkVersion, loadAmmoniteSpark = true)

object LocalClusterSpark12Tests extends LocalClusterSparkTests((1, 2))
object LocalClusterSpark13Tests extends LocalClusterSparkTests((1, 3))
