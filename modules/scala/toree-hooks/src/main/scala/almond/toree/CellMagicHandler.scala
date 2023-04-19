package almond.toree

import almond.api.JupyterApi

@FunctionalInterface
trait CellMagicHandler {
  def handle(name: String, content: String): Either[JupyterApi.ExecuteHookResult, String]
}
