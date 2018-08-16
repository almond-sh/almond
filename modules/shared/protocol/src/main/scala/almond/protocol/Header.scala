package almond.protocol

import java.util.UUID

import argonaut.{DecodeJson, EncodeJson}
import argonaut.ArgonautShapeless._

final case class Header(
  msg_id: String,
  username: String,
  session: String,
  msg_type: String,
  version: Option[String]
  // https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#general-message-format says an ISO 8601 date
  // should be mandatory as of protocol version 5.1, but it seems the classic UI doesn't write it…
  // date: Instant
)

object Header {

  def random(user: String, msgType: MessageType[_], sessionId: String = UUID.randomUUID().toString): Header =
    Header(
      UUID.randomUUID().toString,
      user,
      sessionId,
      msgType.messageType,
      Some(Protocol.versionStr)
    )


  implicit val decoder = DecodeJson.of[Header]
  implicit val encoder = EncodeJson.of[Header]

}
