package almond.protocol

object IsComplete {

  final case class Request(code: String)
  final case class Reply(status: String)


  def requestType = MessageType[Request]("is_complete_request")
  def replyType = MessageType[Reply]("is_complete_reply")

}
