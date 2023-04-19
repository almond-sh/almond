package almond.toree

import almond.api.JupyterApi
import almond.interpreter.api.OutputHandler

import java.util.Locale

import scala.collection.mutable

object CellMagicHook {

  private var userHandlers = new mutable.HashMap[String, CellMagicHandler]

  def addHandler(name: String)(handler: CellMagicHandler): Unit =
    userHandlers += name -> handler

  def clearHandlers(): Unit =
    userHandlers.clear()

  def hook(publish: OutputHandler): JupyterApi.ExecuteHook = {
    val handlers = CellMagicHandlers.handlers(publish)
    code =>
      val nameOpt = code.linesIterator.take(1).toList.collectFirst {
        case name if name.startsWith("%%") =>
          name.stripPrefix("%%")
      }
      nameOpt match {
        case Some(name) =>
          val name0 = name.toLowerCase(Locale.ROOT)
          userHandlers.get(name0).orElse(handlers.get(name0)) match {
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
