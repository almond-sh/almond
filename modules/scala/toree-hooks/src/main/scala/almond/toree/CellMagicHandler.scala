package almond.toree

import almond.api.JupyterApi

trait CellMagicHandler {
  def handle(name: String, content: String): Either[JupyterApi.ExecuteHookResult, String]
}
