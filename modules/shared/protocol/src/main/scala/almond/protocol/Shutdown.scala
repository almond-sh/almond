package almond.protocol

object Shutdown {

  final case class Request(restart: Boolean)
  final case class Reply(restart: Boolean)


  def requestType = MessageType[Request]("shutdown_request")
  def replyType = MessageType[Reply]("shutdown_reply")

}
