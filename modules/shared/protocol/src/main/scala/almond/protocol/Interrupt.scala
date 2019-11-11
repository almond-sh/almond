package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

object Interrupt {

  case object Request
  case object Reply


  def requestType = MessageType[Request.type]("interrupt_request")
  def replyType = MessageType[Reply.type]("interrupt_reply")


  implicit val requestCodec: JsonValueCodec[Request.type] =
    JsonCodecMaker.make(CodecMakerConfig)
  implicit val replyCodec: JsonValueCodec[Reply.type] =
    JsonCodecMaker.make(CodecMakerConfig)

}
