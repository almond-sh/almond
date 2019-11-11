package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

object IsComplete {

  final case class Request(code: String)
  final case class Reply(status: String)


  def requestType = MessageType[Request]("is_complete_request")
  def replyType = MessageType[Reply]("is_complete_reply")


  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make(CodecMakerConfig)
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make(CodecMakerConfig)

}
