package jupyter
package scala

import java.io.{ByteArrayOutputStream, InputStream}

import jupyter.kernel.server.{ ServerApp, ServerAppOptions }
import jupyter.scala.config.ScalaModule

import caseapp._
import com.typesafe.scalalogging.slf4j.LazyLogging

case class JupyterScala(
  options: ServerAppOptions
) extends App with LazyLogging {

  // FIXME Shouldn't sbt-pack put this in system property "prog.name"?
  val progName = "jupyter-scala"

  lazy val isWindows: Boolean =
    Option(System.getProperty("os.name")).exists(_ startsWith "Windows")

  lazy val progSuffix =
    if (isWindows) ".bat"
    else ""

  def progPath =
    Option(System getProperty "prog.home").filterNot(_.isEmpty)
      .map(List(_, "bin", progName + progSuffix).mkString(java.io.File.separator))
      .getOrElse {
        Console.err println "Cannot get program home dir, it is likely we are not run through pre-packaged binaries."
        Console.err println "Please edit the generated file below, and ensure the first item of the 'argv' list points to the path of this program."
        progName
      }

  def readFully(is: InputStream) = {
    val buffer = new ByteArrayOutputStream()

    var nRead = 0
    val data = Array.ofDim[Byte](16384)

    nRead = is.read(data, 0, data.length)
    while (nRead != -1) {
      buffer.write(data, 0, nRead)
      nRead = is.read(data, 0, data.length)
    }

    buffer.flush()
    buffer.toByteArray
  }

  def resource(path: String): Option[Array[Byte]] = {
    for (is <- Option(Thread.currentThread().getContextClassLoader.getResourceAsStream(path))) yield {
      try readFully(is)
      finally is.close()
    }
  }

  val scalaBinaryVersion = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  ServerApp(
    ScalaModule.kernelId,
    ScalaModule.kernel,
    ScalaModule.kernelInfo,
    progPath,
    options,
    logos = Seq(
      resource(s"kernel/scala-$scalaBinaryVersion/resources/logo-64x64.png").map((64, 64) -> _),
      resource(s"kernel/scala-$scalaBinaryVersion/resources/logo-32x32.png").map((32, 32) -> _)
    ).flatten
  )
}

object JupyterScala extends AppOf[JupyterScala] {
  val parser = default
}
