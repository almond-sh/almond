package almond.interpreter.lsp

import almond.protocol.RawJson

/** Language server exchanging LSP messages with frontends via comms
  *
  * See [[LspCommTarget]] for the transport.
  */
trait LanguageServer {

  /** Starts a new LSP session, for a frontend that just connected
    *
    * @param client
    *   allows to send messages to that frontend
    */
  def session(client: LanguageServer.Client): LanguageServer.Session
}

object LanguageServer {

  /** Feature added to `supported_features` in `kernel_info_reply` by kernels providing a language
    * server
    */
  def supportedFeature: String = "lsp"

  /** Target name of the comms carrying LSP messages
    *
    * Each comm corresponds to a distinct LSP session. Each comm message carries one JSON-RPC
    * message, under the `message` key of its data.
    */
  def commTarget: String = "jupyter.lsp"

  trait Client {

    /** Sends a JSON-RPC message (response, request, or notification) to the frontend */
    def send(message: RawJson): Unit

    /** Ends the session */
    def close(): Unit
  }

  trait Session {

    /** Handles a JSON-RPC message (request, notification, or response) from the frontend
      *
      * Called sequentially, in the order messages were received. Implementations should return
      * quickly, and do any long running work elsewhere, as other messages are queued meanwhile.
      */
    def message(message: RawJson): Unit

    /** Called when the frontend ends the session */
    def close(): Unit
  }
}
