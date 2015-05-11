package jupyter
package scala

import jupyter.kernel.server.{ ServerApp, ServerAppOptions }
import jupyter.scala.config.ScalaModule

import caseapp._
import com.typesafe.scalalogging.slf4j.LazyLogging

case class JupyterScala(
  options: ServerAppOptions
) extends App with LazyLogging {

  // FIXME Shouldn't sbt-pack put this in system property "prog.name"?
  val progName = "jupyter-scala"

  def progPath =
    Option(System getProperty "prog.home").filterNot(_.isEmpty).map(_ + s"/bin/$progName") getOrElse {
      Console.err println "Cannot get program home dir, it is likely we are not run through pre-packaged binaries."
      Console.err println "Please edit the generated file below, and ensure the first item of the 'argv' list points to the path of this program."
      progName
    }

  ServerApp(ScalaModule.kernelId, ScalaModule.kernel, ScalaModule.kernelInfo, ScalaModule.languageInfo, progPath, options)
}

object JupyterScala extends AppOf[JupyterScala] {
  val parser = default
}
