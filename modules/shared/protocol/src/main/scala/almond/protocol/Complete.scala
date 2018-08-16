package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson}

object Complete {

  final case class Request(
    code: String,
    cursor_pos: Int
  )

  final case class Reply(
    matches: List[String],
    cursor_start: Int,
    cursor_end: Int,
    metadata: Map[String, String],
    status: String
  )

  object Reply {

    def apply(
      matches: List[String],
      cursor_start: Int,
      cursor_end: Int,
      metadata: Map[String, String]
    ): Reply =
      Reply(
        matches,
        cursor_start,
        cursor_end,
        metadata,
        "ok"
      )

  }


  def requestType = MessageType[Request]("complete_request")
  def replyType = MessageType[Reply]("complete_reply")


  implicit val requestDecoder = DecodeJson.of[Request]
  implicit val replyEncoder = EncodeJson.of[Reply]

}
