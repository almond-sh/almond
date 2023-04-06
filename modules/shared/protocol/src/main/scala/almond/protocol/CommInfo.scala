package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object CommInfo {

  final case class Request(
    target_name: Option[String] = None
  )

  final case class Reply(
    comms: Map[String, Info]
  )

  final case class Info(
    target_name: String
  )

  def requestType = MessageType[Request]("comm_info_request")
  def replyType   = MessageType[Reply]("comm_info_reply")

  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make

}
