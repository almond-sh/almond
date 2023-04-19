package almond.toree

import almond.api.JupyterApi

@FunctionalInterface
trait LineMagicHandler {
  def handle(name: String, values: Seq[String]): Either[JupyterApi.ExecuteHookResult, String]
}
