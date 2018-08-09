package almond.protocol

import almond.protocol.internal.ExtraCodecs._
import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson, JsonObject}

sealed abstract class Comm extends Product with Serializable {
  def comm_id: String
  def data: JsonObject
}

object Comm {

  // Spec says: If the target_name key is not found on the receiving side, then it should immediately reply with a comm_close message to avoid an inconsistent state.
  final case class Open(
    comm_id: String,
    target_name: String,
    data: JsonObject,
    target_module: Option[String] = None // spec says: used to select a module that is responsible for handling the target_name.
  ) extends Comm

  final case class Message(
    comm_id: String,
    data: JsonObject
  ) extends Comm

  final case class Close(
    comm_id: String,
    data: JsonObject
  ) extends Comm


  def openType = MessageType[Open]("comm_open")
  def messageType = MessageType[Message]("comm_msg")
  def closeType = MessageType[Close]("comm_close")


  implicit val openDecoder = DecodeJson.of[Open]
  implicit val openEncoder = EncodeJson.of[Open]

  implicit val messageDecoder = DecodeJson.of[Message]
  implicit val messageEncoder = EncodeJson.of[Message]

  implicit val closeDecoder = DecodeJson.of[Close]
  implicit val closeEncoder = EncodeJson.of[Close]

}
