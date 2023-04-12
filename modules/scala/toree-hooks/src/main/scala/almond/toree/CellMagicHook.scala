package almond.toree

import almond.api.JupyterApi
import almond.interpreter.api.OutputHandler

import java.util.Locale

object CellMagicHook {

  def hook(publish: OutputHandler): JupyterApi.ExecuteHook = {
    val handlers = CellMagicHandlers.handlers(publish)
    code =>
      val nameOpt = code.linesIterator.take(1).toList.collectFirst {
        case name if name.startsWith("%%") =>
          name.stripPrefix("%%")
      }
      nameOpt match {
        case Some(name) =>
          handlers.get(name.toLowerCase(Locale.ROOT)) match {
            case Some(handler) =>
              val content = code.linesWithSeparators.drop(1).mkString
              handler.handle(name, content)
            case None =>
              System.err.println(s"Warning: ignoring unrecognized Toree cell magic $name")
              Right("")
          }
        case None =>
          Right(code)
      }
  }

}
