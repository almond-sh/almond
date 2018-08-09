package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson}

object Interrupt {

  case object Request
  case object Reply


  def requestType = MessageType[Request.type]("interrupt_request")
  def replyType = MessageType[Reply.type]("interrupt_reply")


  implicit val requestDecoder = DecodeJson.of[Request.type]
  implicit val replyEncoder = EncodeJson.of[Reply.type]

}
