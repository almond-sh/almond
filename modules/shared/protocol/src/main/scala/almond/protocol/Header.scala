package almond.protocol

import java.util.UUID

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

final class Header(
  val msg_id: String,
  val username: String,
  val session: String,
  val msg_type: String,
  val version: Option[String]
  // https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#general-message-format says an ISO 8601 date
  // should be mandatory as of protocol version 5.1, but it seems the classic UI doesn't write it…
  // date: Instant
) {
  private def copy(
    msg_id: String = msg_id,
    username: String = username,
    session: String = session,
    msg_type: String = msg_type,
    version: Option[String] = version
  ): Header =
    new Header(
      msg_id = msg_id,
      username = username,
      session = session,
      msg_type = msg_type,
      version = version
    )
  def withMsgId(msgId: String): Header =
    copy(msg_id = msgId)
  def withMsgType(msgType: String): Header =
    copy(msg_type = msgType)

  override def equals(other: Any): Boolean =
    other.isInstanceOf[Header] && {
      val that = other.asInstanceOf[Header]
      msg_id == that.msg_id &&
      username == that.username &&
      session == that.session &&
      msg_type == that.msg_type &&
      version == that.version
    }
}

object Header {

  def apply(
    msg_id: String,
    username: String,
    session: String,
    msg_type: String,
    version: Option[String]
  ): Header =
    new Header(
      msg_id = msg_id,
      username = username,
      session = session,
      msg_type = msg_type,
      version = version
    )

  private final case class Helper(
    msg_id: String,
    username: String,
    session: String,
    msg_type: String,
    version: Option[String]
  ) {
    def toHeader: Header =
      new Header(
        msg_id = msg_id,
        username = username,
        session = session,
        msg_type = msg_type,
        version = version
      )
  }

  private object Helper {
    implicit lazy val codec: JsonValueCodec[Helper] =
      JsonCodecMaker.make
    def fromHeader(header: Header): Helper =
      Helper(
        msg_id = header.msg_id,
        username = header.username,
        session = header.session,
        msg_type = header.msg_type,
        version = header.version
      )
  }

  def random(
    user: String,
    msgType: MessageType[_],
    sessionId: String = UUID.randomUUID().toString
  ): Header =
    Header(
      msg_id = UUID.randomUUID().toString,
      username = user,
      session = sessionId,
      msg_type = msgType.messageType,
      version = Some(Protocol.versionStr)
    )

  implicit lazy val codec: JsonValueCodec[Header] =
    new JsonValueCodec[Header] {
      def nullValue = Option(Helper.codec.nullValue).map(_.toHeader).orNull
      def encodeValue(header: Header, out: JsonWriter) =
        Helper.codec.encodeValue(Helper.fromHeader(header), out)
      def decodeValue(in: JsonReader, default: Header): Header = {
        val value  = RawJson(in.readRawValAsBytes())
        val helper = readFromArray(value.value)(Helper.codec)
        helper.toHeader
      }
    }
}
