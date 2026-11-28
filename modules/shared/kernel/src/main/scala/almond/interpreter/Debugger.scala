package almond.interpreter

import almond.protocol.RawJson

import scala.concurrent.Future

/** Answers the requests of the Jupyter debugger protocol
  *
  * These are Debug Adapter Protocol (DAP) requests, along with a few requests specific to Jupyter
  * (`debugInfo`, `dumpCell`, `inspectVariables`, …), sent by front-ends via `debug_request`
  * messages. The responses are sent back via `debug_reply` messages, and DAP events via
  * `debug_event` messages.
  */
trait Debugger {

  /** Processes a DAP request, and returns the DAP response to it */
  def request(request: RawJson): Future[RawJson]

  /** Sets the function DAP events should be passed to
    *
    * Called once, before any request is passed to [[request]].
    */
  def setEventHandler(handler: RawJson => Unit): Unit
}
