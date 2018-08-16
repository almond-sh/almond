package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson}

object CommInfo {

  final case class Request(
    target_name: Option[String] = None
  )

  final case class Reply(
    comms: Map[String, Info]
  )

  final case class Info(
    target_name: String
  )


  def requestType = MessageType[Request]("comm_info_request")
  def replyType = MessageType[Reply]("comm_info_reply")


  implicit val requestDecoder = DecodeJson.of[Request]
  implicit val replyEncoder = EncodeJson.of[Reply]

}
