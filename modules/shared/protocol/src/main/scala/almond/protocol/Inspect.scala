package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson, Json}

object Inspect {

  final case class Request(
    code: String,
    cursor_pos: Int,
    detail_level: Int // should be 0 or 1
  )

  final case class Reply private[Inspect](
    status: String, // "ok"
    found: Boolean,
    data: Map[String, Json],
    metadata: Map[String, Json]
  ) {
    assert(status == "ok")
  }

  object Reply {
    def apply(
      found: Boolean,
      data: Map[String, Json],
      metadata: Map[String, Json]
    ): Reply =
      Reply("ok", found, data, metadata)
  }


  def requestType = MessageType[Request]("inspect_request")
  def replyType = MessageType[Reply]("inspect_reply")


  implicit val requestDecoder = DecodeJson.of[Request]
  implicit val replyEncoder = EncodeJson.of[Reply]

}
