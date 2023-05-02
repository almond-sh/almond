package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object Connect {

  case object Request

  final case class Reply(
    shell_port: Int,
    iopub_port: Int,
    stdin_port: Int,
    hb_port: Int
  )

  def requestType = MessageType[Request.type]("connect_request")
  def replyType   = MessageType[Reply]("connect_reply")

  implicit val requestCodec: JsonValueCodec[Request.type] =
    JsonCodecMaker.make[Request.type]
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make

}
