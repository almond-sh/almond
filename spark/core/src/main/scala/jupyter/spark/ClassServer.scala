package jupyter.spark

import java.io.File
import java.net.{InetAddress, InetSocketAddress, URI}
import java.nio.file.Files
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import ammonite.runtime.Frame
import org.eclipse.jetty.server.{Request, Server}
import org.eclipse.jetty.server.handler.AbstractHandler

private[spark] class ClassServer(frames: => List[Frame]) {

  private lazy val host =
    sys.env.getOrElse("HOST", InetAddress.getLocalHost.getHostAddress)

  private val socketAddress = InetSocketAddress.createUnresolved(host, Spark.randomPort())

  private val handler = new AbstractHandler {
    def handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse): Unit = {

      val path = target.stripPrefix("/").stripSuffix(".class")
      val item = path.replace('/', '.')

      if (sys.env.contains("DEBUG")) println(s"Queried $item ($target)")

      def fromClassMaps =
        for {
          b <- frames.toStream.flatMap(_.classloader.newFileDict.get(item)).headOption
        } yield b

      def fromDirs =
        frames.toStream
          .flatMap(_.classpath)
          .filterNot(f => f.isFile && f.getName.endsWith(".jar"))
          .map(path.split('/').foldLeft(_)(new File(_, _)))
          .collectFirst { case f if f.exists() => Files.readAllBytes(f.toPath) }

      fromClassMaps orElse fromDirs match {
        case Some(bytes) =>
          if (sys.env.contains("DEBUG")) println(s"Found $item in session byte code")
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

  def stop(): Unit =
    server.stop()

  def uri = new URI(s"http://$socketAddress")

}