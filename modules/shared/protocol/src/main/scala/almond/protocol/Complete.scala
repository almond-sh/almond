package almond.protocol

import almond.protocol.internal.ExtraCodecs._
import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson, JsonObject}

object Complete {

  final case class Request(
    code: String,
    cursor_pos: Int
  )

  final case class Reply(
    matches: List[String],
    cursor_start: Int,
    cursor_end: Int,
    metadata: JsonObject,
    status: String
  )

  object Reply {

    def apply(
      matches: List[String],
      cursor_start: Int,
      cursor_end: Int,
      metadata: JsonObject
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
  implicit val requestEncoder = EncodeJson.of[Request]
  implicit val replyDecoder = DecodeJson.of[Reply]
  implicit val replyEncoder = EncodeJson.of[Reply]

}
