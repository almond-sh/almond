package jupyter.spark
package session

import ammonite.repl.RuntimeAPI
import ammonite.runtime.InterpAPI
import jupyter.spark.internals.Spark
import org.apache.spark.SparkConf
import org.apache.spark.scheduler.{SparkListener, SparkListenerApplicationEnd}
import org.apache.spark.sql.SparkSession

object JupyterSparkSession {

  class Builder(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI) extends SparkSession.Builder {

    // Most of the stuff that are done in sparkInit, sparkEmr, sparkYarn below change global variables.
    // Ideally, these things should be kept in Builder, rather than globally. But the global way
    // also works with Spark 1.x. Alternatively, we could define a basic ad hoc SparkSession for 1.x.

    if (!initialized)
      sparkInit()

    private var appliedJupyterConf = false

    def jupyter(force: Boolean = false): this.type = {
      if (force || !appliedJupyterConf) {
        val conf = JupyterSparkContext.withHooks(new SparkConf)
        config(conf)
        appliedJupyterConf = true
      }
      this
    }

    def yarn(conf: String = Spark.defaultYarnConf()): this.type = {
      master("yarn-client")
      sparkYarn(conf)
      this
    }

    def emr(hadoopVersion: String = "2.7.3"): this.type = {
      sparkEmr(hadoopVersion)
      this
    }

    override def getOrCreate(): SparkSession = {

      jupyter()

      val session = super.getOrCreate()

      JupyterSparkContext.applyContextHooks(session.sparkContext)

      session.sparkContext.addSparkListener(new SparkListener {
        override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd) =
          JupyterSparkContext.applyStopContextHooks(session.sparkContext)
      })

      session
    }
  }

  def builder()(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): Builder =
    new Builder

}
