package jupyter.api

import ammonite.repl.FullRuntimeAPI
import ammonite.runtime.APIHolder

trait JupyterApi {
  /**
   * Jupyter publishing helper
   *
   * Allows to push display items to the front-end or to communicate with
   * widgets through Jupyter comms (WIP)
   */
  implicit def publish: jupyter.api.Publish
}

class JupyterAPIHolder
object JupyterAPIHolder extends APIHolder[FullRuntimeAPI with JupyterApi]