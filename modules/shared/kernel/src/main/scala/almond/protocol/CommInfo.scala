package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

object CommInfo {

  final case class Request(
    target_name: Option[String] = None
  )

  final case class Reply(
    comms: Map[String, Info],
    status: String // no default value here for the value not to be swallowed by the JSON encoder
  )

  object Reply {
    def apply(comms: Map[String, Info]): Reply =
      Reply(comms, "ok")
  }

  final case class Info(
    target_name: String
  )

  def requestType = MessageType[Request]("comm_info_request")
  def replyType   = MessageType[Reply]("comm_info_reply")

  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make
  // comms is a required field, even when empty
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false))

}
