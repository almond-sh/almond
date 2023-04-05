package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object IsComplete {

  final case class Request(code: String)
  final case class Reply(status: String)

  def requestType = MessageType[Request]("is_complete_request")
  def replyType   = MessageType[Reply]("is_complete_reply")

  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make

}
