package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object Inspect {

  final case class Request(
    code: String,
    cursor_pos: Int,
    detail_level: Int // should be 0 or 1
  )

  final case class Reply private[protocol] (
    status: String, // "ok"
    found: Boolean,
    data: Map[String, RawJson],
    metadata: Map[String, RawJson]
  ) {
    assert(status == "ok")
  }

  object Reply {
    def apply(
      found: Boolean,
      data: Map[String, RawJson],
      metadata: Map[String, RawJson]
    ): Reply =
      Reply("ok", found, data, metadata)
  }

  def requestType = MessageType[Request]("inspect_request")
  def replyType   = MessageType[Reply]("inspect_reply")

  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make

}
