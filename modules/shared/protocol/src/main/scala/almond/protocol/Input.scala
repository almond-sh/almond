package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

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


  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make(CodecMakerConfig)

  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make(CodecMakerConfig)


}
