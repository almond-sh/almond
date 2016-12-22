package jupyter

import java.io.IOException
import java.net.ServerSocket

import ammonite.repl.RuntimeAPI
import ammonite.runtime.InterpAPI
import jupyter.spark.internals.Spark
import org.apache.spark.SparkConf

package object spark {

  private[spark] def scalaVersion =
    _root_.scala.util.Properties.versionNumberString

  private[spark] lazy val scalaBinaryVersion =
    scalaVersion
      .split('.')
      .take(2)
      .mkString(".")

  private[spark] lazy val sparkVersion =
    org.apache.spark.SPARK_VERSION

  private[spark] lazy val isSpark2: Boolean =
    sparkVersion.startsWith("2.")

  private[spark] var interpApiOpt = Option.empty[InterpAPI]
  private[spark] var runtimeApiOpt = Option.empty[RuntimeAPI]

  private[spark] def initialized: Boolean = interpApiOpt.nonEmpty

  private def uninitialized(): Nothing =
    sys.error(
      """Spark bridge not initialized - call jupyter.spark.sparkInit() prior to creating a
        |JupyterSparkContext or calling other methods from jupyter.spark.
      """.stripMargin
    )

  private[spark] def interpApi = interpApiOpt.getOrElse(uninitialized())
  private[spark] def runtimeApi = runtimeApiOpt.getOrElse(uninitialized())

  private[spark] implicit class SparkConfExtensions(val conf: SparkConf) {
    def setIfMissingLazy(key: String, value: => String): conf.type = {
      if (conf.getOption(key).isEmpty)
        conf.set(key, value)
      conf
    }
  }

  private lazy val classServer = new internals.ClassServer(runtimeApi.sess.frames)

  private def jars =
    runtimeApi
      .sess
      .frames
      .flatMap(_.classpath)
      .filter(f => f.isFile && f.getName.endsWith(".jar"))

  private def availablePortFrom(from: Int): Int = {
    var socket: ServerSocket = null
    try {
      socket = new ServerSocket(from)
      from
    }
    catch {
      case _: IOException =>
        availablePortFrom(from + 1)
    }
    finally {
      if (socket != null) socket.close()
    }
  }

  def sparkInit()(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): Unit = {

    interpApiOpt = Some(interpApi)
    runtimeApiOpt = Some(runtimeApi)

    val stubModuleName =
      if (isSpark2)
        s"spark-stubs-2_$scalaBinaryVersion"
      else
        s"spark-stubs-1_$scalaBinaryVersion"

    interpApi.load.ivy(
      ("org.jupyter-scala", stubModuleName, jupyter.spark.internals.BuildInfo.version)
    )

    JupyterSparkContext.addConfHook { conf =>

      if (conf.getOption("spark.executor.uri").isEmpty)
        for (execUri <- Option(System.getenv("SPARK_EXECUTOR_URI")))
          conf.set("spark.executor.uri", execUri)

      if (conf.getOption("spark.home").isEmpty)
        Option(System.getenv("SPARK_HOME")) match {
          case Some(sparkHome) =>
            conf.set("spark.home", sparkHome)
          case None =>
            if (isSpark2)
              conf.setIfMissingLazy("spark.yarn.jars", Spark.sparkAssemblyJars().mkString(","))
            else
              conf.setIfMissingLazy("spark.yarn.jar", Spark.sparkAssembly())
        }

      conf
        .setIfMissingLazy(
          "spark.jars",
          jars.mkString(",")
        )
        .setIfMissingLazy("spark.repl.class.uri", classServer.uri.toString)
        .setIfMissingLazy("spark.ui.port", availablePortFrom(4040).toString)
    }


    JupyterSparkContext.addContextHook { sc =>

      for (jar <- jars.map(_.getAbsolutePath))
        sc.addJar(jar)

      interpApi.load.onJarAdded { jars =>
        if (!sc.isStopped)
          for (jar <- jars)
            sc.addJar(jar.getAbsolutePath)
      }

      runtimeApi.onExit { _ =>
        if (!sc.isStopped)
          sc.stop()
      }
    }

  }

  def sparkYarn(conf: String = Spark.defaultYarnConf())(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): Unit = {

    if (!initialized)
      sparkInit()

    // add YARN conf to classpath
    interpApi.load.cp(ammonite.ops.Path(conf))

    interpApi.load.ivy(
      ("org.apache.spark", s"spark-yarn_$scalaBinaryVersion", sparkVersion)
    )

    JupyterSparkContext.addConfHook { conf =>
      conf
        .setIfMissing("spark.master", "yarn-client")
    }
  }

  def sparkEmr(hadoopVersion: String = "2.7.3")(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): Unit = {

    if (!initialized)
      sparkInit()

    val extraDependencies = Seq(
      s"org.apache.hadoop:hadoop-aws:$hadoopVersion",
      s"org.apache.hadoop:hadoop-hdfs:$hadoopVersion",
      "xerces:xercesImpl:2.11.0"
    )

    JupyterSparkContext.addConfHook { conf =>
      if (isSpark2)
        conf.setIfMissingLazy("spark.yarn.jars", Spark.sparkAssemblyJars(extraDependencies).mkString(","))
      else
        conf.setIfMissingLazy("spark.yarn.jar", Spark.sparkAssembly(extraDependencies))
    }
  }

}
