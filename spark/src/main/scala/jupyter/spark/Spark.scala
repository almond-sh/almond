package jupyter.spark

import java.io.{File, IOException}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}
import java.nio.file.Files
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import ammonite.repl.RuntimeAPI
import ammonite.runtime.Frame
import coursier.cli.{CacheOptions, CommonOptions}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SQLContext
import org.eclipse.jetty.server.{Request, Server}
import org.eclipse.jetty.server.handler.AbstractHandler

object Spark {

  lazy val inst: Spark = new Spark(jupyter.api.JupyterAPIHolder.value)

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
      .setIfMissingLazy("spark.ui.port", availablePort(4040).toString)

    if (conf.getOption("spark.executor.uri").isEmpty)
      for (execUri <- Option(System.getenv("SPARK_EXECUTOR_URI")))
        conf.set("spark.executor.uri", execUri)

    if (conf.getOption("spark.home").isEmpty)
      Option(System.getenv("SPARK_HOME")) match {
        case Some(sparkHome) =>
          conf.set("spark.home", sparkHome)
        case None =>
          if (conf.getOption("spark.yarn.jar").isEmpty)
            conf.set("spark.yarn.jar", sparkAssembly)
      }
  }

  def sparkAssembly: String =
    coursier.cli.spark.Assembly.spark(
      scala.util.Properties.versionNumberString,
      org.apache.spark.SPARK_VERSION,
      noDefault = false,
      extraDependencies = Seq(

      ),
      options = CommonOptions(
        keepOptional = false,
        ttl = "",
        quiet = false,
        verbose = caseapp.Tag.of[caseapp.Counter](0),
        progress = false,
        repository = Nil,
        sources = Nil,
        forceVersion = Nil,
        exclude = Nil,
        intransitive = Nil,
        classifier = Nil,
        checksum = Nil,
        benchmark = 0,
        tree = false,
        reverseTree = false,
        profile = Nil,
        cacheOptions = CacheOptions()
      )
    ) match {
      case Left(err) =>
        throw new Exception(err)
      case Right((assembly, _)) =>
        assembly.getAbsolutePath
    }

  private def availablePort(from: Int): Int = {
    var socket: ServerSocket = null
    try {
      socket = new ServerSocket(from)
      from
    }
    catch {
      case _: IOException =>
        availablePort(from + 1)
    }
    finally {
      if (socket != null) socket.close()
    }
  }

  def jars =
    runtimeApi0
      .sess
      .frames
      .flatMap(_.classpath)
      .filter(f => f.isFile && f.getName.endsWith(".jar"))

  @transient lazy val sc: SparkContext = {

    val sc0 = SparkContext.getOrCreate(sparkConf)

    jars.map(_.getAbsolutePath).foreach(sc0.addJar)

    sc0
  }
  @transient lazy val sqlContext: SQLContext =
    new SQLContext(sc)

  @transient lazy val classServer = new ClassServer(runtimeApi0.sess.frames)

}

private[jupyter] class ClassServer(frames: => List[Frame]) {

  private lazy val host =
    sys.env.getOrElse("HOST", InetAddress.getLocalHost.getHostAddress)

  def uri =
    _classServerURI

  private val socketAddress = InetSocketAddress.createUnresolved(host, {
    val s = new ServerSocket(0)
    val port = s.getLocalPort
    s.close()
    port
  })

  private val handler = new AbstractHandler {
    def handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse): Unit = {
      val path = target.stripPrefix("/").split('/').toList.filter(_.nonEmpty)

      def fromClassMaps =
        for {
          List(item) <- Some(path)
          b <- frames.toStream.flatMap(_.classloader.newFileDict.get(item)).headOption
        } yield b

      def fromDirs =
        frames.toStream
          .flatMap(_.classpath)
          .filterNot(f => f.isFile && f.getName.endsWith(".jar"))
          .map(path.foldLeft(_)(new File(_, _)))
          .collectFirst { case f if f.exists() => Files.readAllBytes(f.toPath) }

      fromClassMaps orElse fromDirs match {
        case Some(bytes) =>
          response setContentType "application/octet-stream"
          response setStatus HttpServletResponse.SC_OK
          baseRequest setHandled true
          response.getOutputStream write bytes

        case None =>
          response.setContentType("text/plain")
          response.setStatus(HttpServletResponse.SC_NOT_FOUND)
          baseRequest.setHandled(true)
          response.getWriter.println("not found")
      }
    }
  }

  private val server = new Server(socketAddress)
  server.setHandler(handler)
  server.start()

  val _classServerURI = new URI(s"http://$socketAddress")

}