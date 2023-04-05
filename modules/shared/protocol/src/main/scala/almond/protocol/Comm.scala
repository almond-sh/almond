package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

sealed abstract class Comm extends Product with Serializable {
  def comm_id: String
  def data: RawJson
}

object Comm {

  // Spec says: If the target_name key is not found on the receiving side, then it should immediately reply with a comm_close message to avoid an inconsistent state.
  final case class Open(
    comm_id: String,
    target_name: String,
    data: RawJson,
    target_module: Option[String] =
      None // spec says: used to select a module that is responsible for handling the target_name.
  ) extends Comm

  final case class Message(
    comm_id: String,
    data: RawJson
  ) extends Comm

  final case class Close(
    comm_id: String,
    data: RawJson
  ) extends Comm

  def openType    = MessageType[Open]("comm_open")
  def messageType = MessageType[Message]("comm_msg")
  def closeType   = MessageType[Close]("comm_close")

  implicit val openCodec: JsonValueCodec[Open] =
    JsonCodecMaker.make
  implicit val messageCodec: JsonValueCodec[Message] =
    JsonCodecMaker.make
  implicit val closeCodec: JsonValueCodec[Close] =
    JsonCodecMaker.make

}
