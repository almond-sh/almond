package jupyter

import ammonite.repl.RuntimeAPI
import ammonite.runtime.InterpAPI

package object spark {

  lazy val sparkConf = Spark.inst.sparkConf
  lazy val sc = Spark.inst.sc
  lazy val sqlContext = Spark.inst.sqlContext

  private[spark] def scalaVersion =
    _root_.scala.util.Properties.versionNumberString

  private[spark] def scalaBinaryVersion =
    scalaVersion
      .split('.')
      .take(2)
      .mkString(".")

  private[spark] def sparkVersion =
    org.apache.spark.SPARK_VERSION

  def sparkInit()(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): Unit =
    Spark.init()

  def sparkYarn(conf: String = Spark.defaultYarnConf()): Unit = {

    Spark.interpApi.load.cp(ammonite.ops.Path(conf))

    Spark.interpApi.load.ivy(
      ("org.apache.spark", s"spark-yarn_$scalaBinaryVersion", sparkVersion)
    )

    sparkConf
      .setMaster("yarn-client")
  }

  def sparkEmr(hadoopVersion: String): Unit = {

    val extraDependencies = Seq(
      s"org.apache.hadoop:hadoop-aws:$hadoopVersion",
      s"org.apache.hadoop:hadoop-hdfs:$hadoopVersion",
      "xerces:xercesImpl:2.11.0"
    )

    if (Spark.isSpark2)
      sparkConf
        .set("spark.yarn.jars", Spark.sparkAssemblyJars(extraDependencies: _*).mkString(","))
    else
      sparkConf
        .set("spark.yarn.jar", Spark.sparkAssembly(extraDependencies: _*))
  }

}
