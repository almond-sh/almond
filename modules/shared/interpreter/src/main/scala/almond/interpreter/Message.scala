package almond.interpreter

import java.nio.charset.StandardCharsets
import java.util.UUID

import fs2.async.mutable.Queue
import fs2.Stream
import almond.channels.{Channel, Message => RawMessage}
import almond.protocol.{Header, MessageType}
import almond.interpreter.util.BetterPrinter
import argonaut.{DecodeJson, EncodeJson, Json}
import argonaut.Argonaut._
import cats.effect.IO

/**
  * Fully-decoded message, with content of type [[T]]
  */
final case class Message[T](
  header: Header,
  content: T,
  parent_header: Option[Header] = None,
  idents: List[Seq[Byte]] = Nil,
  metadata: Map[String, Json] = Map.empty
) {

  def messageType: MessageType[T] =
    MessageType(header.msg_type)

  private def replyHeader(messageType: MessageType[_]): Header =
    header.copy(
      msg_id = UUID.randomUUID().toString,
      msg_type = messageType.messageType
    )

  /**
    * Creates a response [[Message]] to this [[Message]], to be sent on the [[Channel.Publish]] channel.
    *
    * Sets the identity of the response message for the [[Channel.Publish]] channel.
    */
  def publish[U](
    messageType: MessageType[U],
    content: U,
    metadata: Map[String, Json] = Map.empty,
    ident: Option[String] = None
  ): Message[U] =
    Message(
      replyHeader(messageType),
      content,
      Some(header),
      List(ident.getOrElse(messageType.messageType).getBytes(StandardCharsets.UTF_8).toSeq),
      metadata
    )

  /**
    * Creates a response [[Message]] to this [[Message]], to be sent on the [[Channel.Requests]] channel.
    */
  def reply[U](
    messageType: MessageType[U],
    content: U,
    metadata: Map[String, Json] = Map.empty
  ): Message[U] =
    Message(
      replyHeader(messageType),
      content,
      Some(header),
      idents,
      metadata
    )

  /**
    * Encodes this [[Message]] as a [[RawMessage]].
    */
  def asRawMessage(implicit encoder: EncodeJson[T]): RawMessage =
    RawMessage(
      idents,
      BetterPrinter.noSpaces(header.asJson),
      parent_header.fold("{}")(h => BetterPrinter.noSpaces(h.asJson)),
      BetterPrinter.noSpaces(metadata.asJson),
      BetterPrinter.noSpaces(content.asJson)
    )


  // helpers

  def decodeAs[U](implicit ev: T =:= Json, decoder: DecodeJson[U]): Either[String, Message[U]] =
    decoder
      .decodeJson(ev(content))
      .map(t => copy(content = t))
      .result
      .left
      .map(c => s"${c._1} (${c._2})")

  def on(channel: Channel)(implicit encoder: EncodeJson[T]): (Channel, RawMessage) =
    channel -> asRawMessage

  def streamOn[F[_]](channel: Channel)(implicit encoder: EncodeJson[T]): Stream[F, (Channel, RawMessage)] =
    Stream(channel -> asRawMessage)

  def enqueueOn(channel: Channel, queue: Queue[IO, (Channel, RawMessage)])(implicit encoder: EncodeJson[T]): IO[Unit] =
    queue.enqueue1(channel -> asRawMessage)

  def clearParentHeader: Message[T] =
    copy(parent_header = None)
  def clearMetadata: Message[T] =
    copy(metadata = Map.empty)

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

  def parse[T: DecodeJson](rawMessage: RawMessage): Either[String, Message[T]] =
    for {
      header <- rawMessage.header.decodeEither[Header].right
      metaData <- rawMessage.metadata.decodeEither[Map[String, Json]].right
      content <- rawMessage.content.decodeEither[T].right
    } yield {
      // FIXME Parent header is optional, but this unnecessarily traps errors
      val parentHeaderOpt = rawMessage.parentHeader.decodeEither[Header].right.toOption

      Message(header, content, parentHeaderOpt, rawMessage.idents.toList, metaData)
    }

}
