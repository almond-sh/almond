package jupyter.spark

import java.io.{File, IOException}
import java.net.ServerSocket

import ammonite.repl.RuntimeAPI
import ammonite.runtime.InterpAPI
import coursier.cli.{CacheOptions, CommonOptions, Helper}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SQLContext

object Spark {

  private[spark] def isSpark2: Boolean =
    org.apache.spark.SPARK_VERSION.startsWith("2.")

  private[spark] var interpApiOpt = Option.empty[InterpAPI]
  private[spark] var runtimeApiOpt = Option.empty[RuntimeAPI]

  private def uninitialized(): Nothing =
    sys.error(
      """Spark bridge not initialized - call jupyter.spark.sparkInit() prior to calling
        |a method from jupyter.spark.
      """.stripMargin
    )

  private[spark] def interpApi = interpApiOpt.getOrElse(uninitialized())
  private[spark] def runtimeApi = runtimeApiOpt.getOrElse(uninitialized())

  def init()(implicit interpApi: InterpAPI, runtimeApi: RuntimeAPI): Unit = {
    interpApiOpt = Some(interpApi)
    runtimeApiOpt = Some(runtimeApi)

    val stubModuleName =
      if (isSpark2)
        s"spark-stubs-2_$scalaBinaryVersion"
      else
        s"spark-stubs-1_$scalaBinaryVersion"

    interpApi.load.ivy(
      ("org.jupyter-scala", stubModuleName, jupyter.scala.BuildInfo.version)
    )
  }

  lazy val inst: Spark = new Spark(runtimeApi)

  def defaultYarnConf() =
    sys.env.get("YARN_CONF_DIR")
      .orElse(if (new File("/etc/hadoop/conf").exists()) Some("/etc/hadoop/conf") else None)
      .getOrElse {
        throw new NoSuchElementException("YARN_CONF_DIR not set and /etc/hadoop/conf not found")
      }

  def hadoopVersion =
    sys.env.get("HADOOP_VERSION")
      .orElse(sys.props.get("HADOOP_VERSION"))
      .getOrElse("2.7.3")

  def sparkAssembly(extraDependencies: String*): String =
    coursier.cli.spark.Assembly.spark(
      scalaBinaryVersion,
      sparkVersion,
      hadoopVersion,
      default = true,
      extraDependencies.toList,
      options = CommonOptions(
        checksum = List("SHA-1") // should not be required with coursier > 1.0.0-M14-9
      )
    ) match {
      case Left(err) =>
        throw new Exception(err)
      case Right((assembly, _)) =>
        assembly.getAbsolutePath
    }

  def sparkBaseDependencies(scalaVersion: String, sparkVersion: String) = {

    val extra =
      if (sparkVersion.startsWith("2."))
        Seq()
      else
        Seq(
          s"org.apache.spark:spark-bagel_$scalaVersion:$sparkVersion"
        )

    Seq(
      s"org.apache.spark:spark-core_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-mllib_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-streaming_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-graphx_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-sql_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-repl_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-yarn_$scalaVersion:$sparkVersion"
    ) ++ extra
  }

  def sparkAssemblyJars(extraDependencies: String*) = {

    val base = sparkBaseDependencies(
      scalaBinaryVersion,
      sparkVersion
    )
    val helper = new Helper(CommonOptions(), extraDependencies ++ base)

    helper.fetch(sources = false, javadoc = false, artifactTypes = Set("jar"))
      .map(_.getAbsolutePath)
  }

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

  private[spark] def randomPort(): Int = {
    val s = new ServerSocket(0)
    val port = s.getLocalPort
    s.close()
    port
  }

}

class Spark(runtimeApi0: RuntimeAPI) extends Serializable {

  val sparkConf = new SparkConf()

  /** Called before creation of the `SparkContext` to setup the `SparkConf`. */
  def setConfDefaults(conf: SparkConf): Unit = {
    implicit class SparkConfExtensions(val conf: SparkConf) {
      def setIfMissingLazy(key: String, value: => String): conf.type = {
        if (conf.getOption(key).isEmpty)
          conf.set(key, value)
        conf
      }
    }

    conf
      .setIfMissingLazy(
        "spark.jars",
        jars.mkString(",")
      )
      .setIfMissingLazy("spark.repl.class.uri", classServer.uri.toString)
      .setIfMissingLazy("spark.ui.port", Spark.availablePortFrom(4040).toString)

    if (conf.getOption("spark.executor.uri").isEmpty)
      for (execUri <- Option(System.getenv("SPARK_EXECUTOR_URI")))
        conf.set("spark.executor.uri", execUri)

    if (conf.getOption("spark.home").isEmpty)
      Option(System.getenv("SPARK_HOME")) match {
        case Some(sparkHome) =>
          conf.set("spark.home", sparkHome)
        case None =>
          if (Spark.isSpark2) {
            if (conf.getOption("spark.yarn.jars").isEmpty)
              conf.set("spark.yarn.jars", Spark.sparkAssemblyJars().mkString(","))
          } else {
            if (conf.getOption("spark.yarn.jar").isEmpty)
              conf.set("spark.yarn.jar", Spark.sparkAssembly())
          }
      }
  }

  def jars =
    runtimeApi0
      .sess
      .frames
      .flatMap(_.classpath)
      .filter(f => f.isFile && f.getName.endsWith(".jar"))

  @transient lazy val sc: SparkContext = {

    setConfDefaults(sparkConf)

    println("Creating SparkContext")
    val sc0 = new SparkContext(sparkConf) {
      override def stop() = {
        classServer.stop()
        super.stop()
      }
    }

    println("Adding session JARs to SparkContext")
    jars.map(_.getAbsolutePath).foreach(sc0.addJar)

    Spark.interpApi
      .load
      .onJarAdded { jars =>
        jars.foreach(jar =>
          sc0.addJar(jar.getAbsolutePath)
        )
      }

    Spark.runtimeApi
      .onExit { value =>
        sc0.stop()
      }

    println("SparkContext initialized")

    sc0
  }
  @transient lazy val sqlContext: SQLContext =
    new SQLContext(sc)

  @transient lazy val classServer = new ClassServer(runtimeApi0.sess.frames)

}