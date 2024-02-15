package almond.kernel

import almond.protocol.RawJson
import almond.channels.{Channel, Message => RawMessage}
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import java.nio.charset.StandardCharsets

final case class MessageFile(
  messages: List[MessageFile.Message]
) {
  def asJson: RawJson =
    RawJson(writeToArray(this)(MessageFile.codec))
  def parsedMessages: Seq[(Channel, RawMessage)] =
    messages.map(m => (m.channel, m.rawMessage))
}

object MessageFile {

  def from(messages: Seq[(Channel, RawMessage)]): MessageFile =
    MessageFile(
      messages.toList.map {
        case (c, m) =>
          Message.fromRawMessage(c, m)
      }
    )

  def decode(content: RawJson): Either[JsonReaderException, MessageFile] =
    try Right(readFromArray(content.value)(codec))
    catch {
      case e: JsonReaderException =>
        Left(e)
    }

  private implicit val channelCodec: JsonValueCodec[Channel] =
    new JsonValueCodec[Channel] {
      val stringCodec: JsonValueCodec[String] = JsonCodecMaker.make
      def nullValue                           = Channel.Requests
      def decodeValue(in: JsonReader, default: Channel) = {
        val s = stringCodec.decodeValue(in, stringCodec.nullValue)
        Channel.map.getOrElse(
          s,
          throw new Exception(s"Malformed channel: '$s'")
        )
      }
      def encodeValue(x: Channel, out: JsonWriter) =
        stringCodec.encodeValue(x.name, out)
    }

  val codec: JsonValueCodec[MessageFile] = JsonCodecMaker.make

  final case class Message(
    header: RawJson,
    metadata: RawJson,
    content: RawJson,
    parent_header: RawJson,
    channel: Channel,
    idents: Seq[Seq[Byte]]
  ) {
    def rawMessage: RawMessage =
      RawMessage(
        idents,
        header.value,
        parent_header.value,
        metadata.value,
        content.value
      )
  }

  object Message {
    def fromRawMessage(channel: Channel, rawMessage: RawMessage): Message =
      Message(
        RawJson(rawMessage.header),
        RawJson(rawMessage.metadata),
        RawJson(rawMessage.content),
        RawJson(rawMessage.parentHeader),
        channel,
        rawMessage.idents
      )
  }

}
