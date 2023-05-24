package almond.interpreter

import java.nio.charset.StandardCharsets
import java.util.UUID

import almond.channels.{Channel, Message => RawMessage}
import almond.protocol.{Header, MessageType, RawJson}
import cats.effect.IO
import cats.effect.std.Queue
import fs2.Stream

import scala.util.Try

/** Fully-decoded message, with content of type [[T]]
  */
final case class Message[T](
  header: Header,
  content: T,
  parent_header: Option[Header] = None,
  idents: List[Seq[Byte]] = Nil,
  metadata: RawJson = RawJson.emptyObj
) {

  import com.github.plokhotnyuk.jsoniter_scala.core._
  import Message._

  def messageType: MessageType[T] =
    MessageType(header.msg_type)

  private def replyHeader(messageType: MessageType[_]): Header =
    header.copy(
      msg_id = UUID.randomUUID().toString,
      msg_type = messageType.messageType
    )

  /** Creates a response [[Message]] to this [[Message]], to be sent on the [[Channel.Publish]]
    * channel.
    *
    * Sets the identity of the response message for the [[Channel.Publish]] channel.
    */
  def publish[U](
    messageType: MessageType[U],
    content: U,
    metadata: RawJson = RawJson.emptyObj,
    ident: Option[String] = None
  ): Message[U] =
    Message(
      replyHeader(messageType),
      content,
      Some(header),
      List(ident.getOrElse(messageType.messageType).getBytes(StandardCharsets.UTF_8).toSeq),
      metadata
    )

  /** Creates a response [[Message]] to this [[Message]], to be sent on the [[Channel.Requests]]
    * channel.
    */
  def reply[U](
    messageType: MessageType[U],
    content: U,
    metadata: RawJson = RawJson.emptyObj
  ): Message[U] =
    Message(
      replyHeader(messageType),
      content,
      Some(header),
      idents,
      metadata
    )

  /** Encodes this [[Message]] as a [[RawMessage]].
    */
  def asRawMessage(implicit encoder: JsonValueCodec[T]): RawMessage =
    RawMessage(
      idents,
      writeToArray(header),
      parent_header.fold("{}".getBytes(StandardCharsets.UTF_8))(h => writeToArray(h)),
      writeToArray(metadata),
      writeToArray(content)
    )

  // helpers

  def decodeAs[U](implicit
    ev: T =:= RawJson,
    decoder: JsonValueCodec[U]
  ): Either[Throwable, Message[U]] =
    Try(readFromArray[U](ev(content).value))
      .toEither
      .map(t => copy(content = t))

  def on(channel: Channel)(implicit encoder: JsonValueCodec[T]): (Channel, RawMessage) =
    channel -> asRawMessage

  def streamOn[F[_]](channel: Channel)(implicit
    encoder: JsonValueCodec[T]
  ): Stream[F, (Channel, RawMessage)] =
    Stream(channel -> asRawMessage)

  def enqueueOn(channel: Channel, queue: Queue[IO, (Channel, RawMessage)])(implicit
    encoder: JsonValueCodec[T]
  ): IO[Unit] =
    queue.offer(channel -> asRawMessage)

  def enqueueOn0(channel: Channel, queue: Queue[IO, Either[Throwable, (Channel, RawMessage)]])(
    implicit encoder: JsonValueCodec[T]
  ): IO[Unit] =
    queue.offer(Right(channel -> asRawMessage))

  def clearParentHeader: Message[T] =
    copy(parent_header = None)
  def clearMetadata: Message[T] =
    copy(metadata = RawJson.emptyObj)

  def update[U](msgType: MessageType[U], newContent: U): Message[U] =
    copy(
      header = header.copy(
        msg_id = UUID.randomUUID().toString,
        msg_type = msgType.messageType
      ),
      content = newContent
    )
}

object Message {

  import com.github.plokhotnyuk.jsoniter_scala.core._

  def parse[T: JsonValueCodec](rawMessage: RawMessage): Either[Throwable, Message[T]] =
    for {
      header  <- Try(readFromArray[Header](rawMessage.header)).toEither
      content <- Try(readFromArray[T](rawMessage.content)).toEither
    } yield {
      // FIXME Parent header is optional, but this unnecessarily traps errors
      val parentHeaderOpt = Try(readFromArray[Header](rawMessage.parentHeader)).toOption

      Message(
        header,
        content,
        parentHeaderOpt,
        rawMessage.idents.toList,
        RawJson(rawMessage.metadata)
      )
    }

}
