package almond.protocol

/** Messages of the Jupyter debugger protocol
  *
  * Their content is that of Debug Adapter Protocol (DAP) messages: `debug_request` messages, sent
  * on the control channel, wrap DAP requests, `debug_reply` messages wrap the DAP responses to
  * these, and `debug_event` messages, published on IOPub, wrap DAP events.
  *
  * See https://jupyter-client.readthedocs.io/en/latest/messaging.html#debug-request
  */
object Debug {

  def requestType = MessageType[RawJson]("debug_request")
  def replyType   = MessageType[RawJson]("debug_reply")
  def eventType   = MessageType[RawJson]("debug_event")

}
