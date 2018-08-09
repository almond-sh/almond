package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson}

object Connect {

  case object Request

  final case class Reply(
    shell_port: Int,
    iopub_port: Int,
    stdin_port: Int,
    hb_port: Int
  )


  def requestType = MessageType[Request.type]("connect_request")
  def replyType = MessageType[Reply]("connect_reply")


  implicit val requestDecoder = DecodeJson.of[Request.type]
  implicit val replyEncoder = EncodeJson.of[Reply]

}
