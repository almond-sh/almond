package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object Interrupt {

  case object Request
  case object Reply

  def requestType = MessageType[Request.type]("interrupt_request")
  def replyType   = MessageType[Reply.type]("interrupt_reply")

  implicit val requestCodec: JsonValueCodec[Request.type] =
    JsonCodecMaker.make[Request.type]
  implicit val replyCodec: JsonValueCodec[Reply.type] =
    JsonCodecMaker.make[Reply.type]

}
