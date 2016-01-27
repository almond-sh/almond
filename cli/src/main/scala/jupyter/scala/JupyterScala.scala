package jupyter
package scala

import java.io.{ ByteArrayOutputStream, InputStream }

import jupyter.kernel.interpreter.InterpreterKernel
import jupyter.kernel.server.{ ServerApp, ServerAppOptions }
import jupyter.scala.config.ScalaModule

import caseapp._

import com.typesafe.scalalogging.slf4j.LazyLogging

import scalaz.\/

case class JupyterScala(options: ServerAppOptions) extends App with LazyLogging {

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

  val scalaBinaryVersion = _root_.scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  val mainJar = sys.props.get("coursier.mainJar").getOrElse {
    Console.err.println("Cannot get main JAR path. Is jupyter-scala launched via its launcher?")
    sys.exit(1)
  }

  val mainArgs0 = Stream.from(0)
    .map(i => s"coursier.main.arg-$i")
    .map(sys.props.get)
    .takeWhile(_.nonEmpty)
    .collect { case Some(arg) => arg }
    .toVector

  val mainArgs =
    if (options.force) {
      // hack-hack not to be called with --force from kernel.json
      val forceIdx = mainArgs0.lastIndexOf("--force")
      if (forceIdx >= 0)
        mainArgs0.take(forceIdx) ++ mainArgs0.drop(forceIdx + 1)
      else
        mainArgs0
    } else
      mainArgs0

  ServerApp(
    ScalaModule.kernelId,
    new InterpreterKernel {
      def apply() = \/.fromTryCatchNonFatal(ScalaInterpreter())
    },
    ScalaModule.kernelInfo,
    "java",
    options,
    extraProgArgs = Seq("-jar", mainJar) ++ mainArgs,
    logos = Seq(
      resource(s"kernel/scala-$scalaBinaryVersion/resources/logo-64x64.png").map((64, 64) -> _),
      resource(s"kernel/scala-$scalaBinaryVersion/resources/logo-32x32.png").map((32, 32) -> _)
    ).flatten
  )
}

object JupyterScala extends AppOf[JupyterScala] {
  val parser = default
}
