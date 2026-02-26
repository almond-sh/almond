package almond.protocol

import java.time.Instant
import java.util.UUID

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

final class Header(
  val msg_id: String,
  val username: String,
  val session: String,
  val msg_type: String,
  val version: Option[String],
  val date: Option[String],
  val rawContentOpt: Option[RawJson]
) {
  private def copyClearRawContent(
    msg_id: String = msg_id,
    username: String = username,
    session: String = session,
    msg_type: String = msg_type,
    version: Option[String] = version,
    date: Option[String] = date
  ): Header =
    new Header(
      msg_id = msg_id,
      username = username,
      session = session,
      msg_type = msg_type,
      version = version,
      date = date,
      rawContentOpt = None
    )
  def withMsgId(msgId: String): Header =
    copyClearRawContent(msg_id = msgId, date = Some(Instant.now().toString))
  def withMsgType(msgType: String): Header =
    copyClearRawContent(msg_type = msgType, date = Some(Instant.now().toString))
  def clearRawContent(): Header =
    copyClearRawContent()

  override def equals(other: Any): Boolean =
    other.isInstanceOf[Header] && {
      val that = other.asInstanceOf[Header]
      msg_id == that.msg_id &&
      username == that.username &&
      session == that.session &&
      msg_type == that.msg_type &&
      version == that.version &&
      date == that.date &&
      rawContentOpt == that.rawContentOpt
    }
}

object Header {

  def apply(
    msg_id: String,
    username: String,
    session: String,
    msg_type: String,
    version: Option[String],
    date: Option[String] = Some(Instant.now().toString)
  ): Header =
    new Header(
      msg_id = msg_id,
      username = username,
      session = session,
      msg_type = msg_type,
      version = version,
      date = date,
      rawContentOpt = None
    )

  def apply(
    msg_id: String,
    username: String,
    session: String,
    msg_type: String,
    version: Option[String],
    rawContentOpt: Option[RawJson],
    date: Option[String]
  ): Header =
    new Header(
      msg_id = msg_id,
      username = username,
      session = session,
      msg_type = msg_type,
      version = version,
      date = date,
      rawContentOpt = rawContentOpt
    )

  private final case class Helper(
    msg_id: String,
    username: String,
    session: String,
    msg_type: String,
    version: Option[String],
    date: Option[String] = None
  ) {
    def toHeader(rawContentOpt: Option[RawJson]): Header =
      new Header(
        msg_id = msg_id,
        username = username,
        session = session,
        msg_type = msg_type,
        version = version,
        date = date,
        rawContentOpt = rawContentOpt
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
        version = header.version,
        date = header.date
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
      version = Some(Protocol.versionStr),
      date = Some(Instant.now().toString)
    )

  implicit lazy val codec: JsonValueCodec[Header] =
    new JsonValueCodec[Header] {
      def nullValue = Option(Helper.codec.nullValue).map(_.toHeader(None)).orNull
      def encodeValue(header: Header, out: JsonWriter) =
        header.rawContentOpt match {
          case Some(rawContent) =>
            out.writeRawVal(rawContent.value)
          case None =>
            Helper.codec.encodeValue(Helper.fromHeader(header), out)
        }
      def decodeValue(in: JsonReader, default: Header): Header = {
        val value  = RawJson(in.readRawValAsBytes())
        val helper = readFromArray(value.value)(Helper.codec)
        helper.toHeader(Some(value))
      }
    }
}
