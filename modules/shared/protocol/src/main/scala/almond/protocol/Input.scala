package almond.protocol

object Input {

  final case class Request(
    prompt: String,
    password: Boolean
  )

  final case class Reply(
    value: String
  )

  def requestType = MessageType[Request]("input_request")
  def replyType = MessageType[Reply]("input_reply")

}
