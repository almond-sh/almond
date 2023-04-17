package almond.toree

import almond.api.JupyterApi
import almond.interpreter.api.DisplayData.ContentType
import almond.interpreter.api.{DisplayData, OutputHandler}

object CellMagicHandlers {

  class DisplayDataHandler(publish: OutputHandler, contentType: String) extends CellMagicHandler {
    def handle(name: String, content: String): Either[JupyterApi.ExecuteHookResult, String] = {
      publish.display(DisplayData(Map(contentType -> content)))
      Right("")
    }
  }

  def handlers(publish: OutputHandler) = Map(
    "html"       -> new DisplayDataHandler(publish, ContentType.html),
    "javascript" -> new DisplayDataHandler(publish, ContentType.js)
  )

  def handlerKeys: Iterable[String] =
    handlers(OutputHandler.NopOutputHandler).keys

}
