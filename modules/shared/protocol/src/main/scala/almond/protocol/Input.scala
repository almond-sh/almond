package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson}

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


  implicit val requestDecoder = DecodeJson.of[Request]
  implicit val requestEncoder = EncodeJson.of[Request]

  implicit val replyDecoder = DecodeJson.of[Reply]
  implicit val replyEncoder = EncodeJson.of[Reply]

}
