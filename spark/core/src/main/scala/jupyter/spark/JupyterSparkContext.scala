package jupyter.spark

import ammonite.repl.RuntimeAPI
import ammonite.runtime.InterpAPI
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable

class JupyterSparkContext(config: SparkConf)(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI)
  extends SparkContext(JupyterSparkContext.withHooksEnsureInitialized(config)) {

  def this()(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI) = this(new SparkConf)

  JupyterSparkContext.applyContextHooks(this)

  override def stop() = {
    JupyterSparkContext.applyStopContextHooks(this)
    super.stop()
  }
}

object JupyterSparkContext {

  private val confHooks = new mutable.ListBuffer[SparkConf => SparkConf]
  def addConfHook(f: SparkConf => SparkConf): Unit =
    confHooks += f
  def withHooks(conf: SparkConf): SparkConf =
    (conf /: confHooks)((conf, f) => f(conf))
  def withHooksEnsureInitialized(conf: SparkConf)(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): SparkConf = {

    if (!initialized)
      sparkInit()

    withHooks(conf)
  }

  private val contextHooks = new mutable.ListBuffer[SparkContext => Unit]
  def addContextHook(f: SparkContext => Unit): Unit =
    contextHooks += f
  def applyContextHooks(sc: SparkContext): sc.type = {
    contextHooks.foreach(_(sc))
    sc
  }

  private val stopContextHooks = new mutable.ListBuffer[SparkContext => Unit]
  def addStopContextHook(f: SparkContext => Unit): Unit =
    stopContextHooks += f
  def applyStopContextHooks(sc: SparkContext): sc.type = {
    stopContextHooks.foreach(_(sc))
    sc
  }

}
