package almond.protocol

object Interrupt {

  case object Request
  case object Reply


  def requestType = MessageType[Request.type]("interrupt_request")
  def replyType = MessageType[Reply.type]("interrupt_reply")

}
