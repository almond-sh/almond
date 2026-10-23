package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object Interrupt {

  case object Request
  final case class Reply(
    status: String // no default value here for the value not to be swallowed by the JSON encoder
  )

  object Reply {
    def apply(): Reply =
      Reply("ok")
  }

  def requestType = MessageType[Request.type]("interrupt_request")
  def replyType   = MessageType[Reply]("interrupt_reply")

  implicit val requestCodec: JsonValueCodec[Request.type] =
    JsonCodecMaker.make[Request.type]
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make

}
