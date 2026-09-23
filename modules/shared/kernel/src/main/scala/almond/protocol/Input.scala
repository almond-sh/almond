package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object Input {

  final case class Request(
    prompt: String,
    password: Boolean
  )

  final case class Reply(
    value: String
  )

  def requestType = MessageType[Request]("input_request")
  def replyType   = MessageType[Reply]("input_reply")

  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make

}
