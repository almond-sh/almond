package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson}

object IsComplete {

  final case class Request(code: String)
  final case class Reply(status: String)


  def requestType = MessageType[Request]("is_complete_request")
  def replyType = MessageType[Reply]("is_complete_reply")


  implicit val requestDecoder = DecodeJson.of[Request]
  implicit val replyEncoder = EncodeJson.of[Reply]

}
