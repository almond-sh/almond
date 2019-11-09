package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

object Shutdown {

  final case class Request(restart: Boolean)
  final case class Reply(restart: Boolean)


  def requestType = MessageType[Request]("shutdown_request")
  def replyType = MessageType[Reply]("shutdown_reply")


  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make(CodecMakerConfig)
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make(CodecMakerConfig)

}
