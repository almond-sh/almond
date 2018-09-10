package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson}

object Shutdown {

  final case class Request(restart: Boolean)
  final case class Reply(restart: Boolean)


  def requestType = MessageType[Request]("shutdown_request")
  def replyType = MessageType[Reply]("shutdown_reply")


  implicit val requestEncoder = EncodeJson.of[Request]
  implicit val requestDecoder = DecodeJson.of[Request]
  implicit val replyDecoder = EncodeJson.of[Reply]

}
