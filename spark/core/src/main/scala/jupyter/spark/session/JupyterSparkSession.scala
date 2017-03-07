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

    if (!initialized)
      sparkInit()

    private var appliedJupyterConf = false

    def jupyterConf(force: Boolean = false): this.type = {
      if (force || !appliedJupyterConf) {
        val conf = JupyterSparkContext.withHooks(new SparkConf)
        config(conf)
        appliedJupyterConf = true
      }
      this
    }

    def yarn(conf: String = Spark.defaultYarnConf()): this.type = {
      sparkYarn(conf)
      this
    }

    def emr(hadoopVersion: String = "2.7.3"): this.type = {
      sparkEmr(hadoopVersion)
      this
    }

    override def getOrCreate(): SparkSession = {

      jupyterConf()

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
