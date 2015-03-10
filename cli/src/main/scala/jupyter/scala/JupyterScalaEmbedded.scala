package jupyter
package scala

import caseapp._
import com.typesafe.scalalogging.slf4j.LazyLogging
import kernel.server.{ ServerApp, ServerAppOptions }

case class JupyterScalaEmbedded(
  options: ServerAppOptions,
  id: String
) extends App with LazyLogging {

  // FIXME Shouldn't sbt-pack put this in system property "prog.name"?
  val progName = "jupyter-scala-embedded"

  def progPath =
    Option(System getProperty "prog.home").filterNot(_.isEmpty).map(_ + s"/bin/$progName") getOrElse {
      Console.err println "Cannot get program home dir, it is likely we are not run through pre-packaged binaries."
      Console.err println "Please edit the generated file below, and ensure the first item of the 'argv' list points to the path of this program."
      progName
    }

  ServerApp(Some(id).filter(_.nonEmpty) getOrElse EmbeddedScalaModule.kernelId, EmbeddedScalaModule.kernel, EmbeddedScalaModule.kernelInfo, progPath, options)
}

object JupyterScalaEmbedded extends AppOf[JupyterScalaEmbedded] {
  val parser = default
}

class JupyterScalaEmbeddedConscriptLaunch extends _root_.xsbti.AppMain {
  def run(config: _root_.xsbti.AppConfiguration) =
    try {
      JupyterScalaEmbedded.main(config.arguments)
      Exit(0)
    } catch {
      case _: Exception =>
        Exit(1)
    }

  case class Exit(code: Int) extends _root_.xsbti.Exit
}