package almond.kernel

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.protocol.Codecs.stringCodec
import almond.protocol.Execute.DisplayData
import almond.protocol.{Execute, MessageType, RawJson}
import almond.kernel.KernelTests.threads
import cats.effect.IO
import fs2.concurrent.Queue
import fs2.{Pipe, Stream}

import scala.collection.mutable
import scala.util.Try

final case class ClientStreams(
  source: Stream[IO, (Channel, RawMessage)],
  sink: Pipe[IO, (Channel, RawMessage), Unit],
  generatedMessages: mutable.ListBuffer[Either[(Channel, Message[RawJson]), (Channel, Message[RawJson])]]
) {

  import com.github.plokhotnyuk.jsoniter_scala.core._
  import ClientStreams.RawJsonOps

  // to kernel
  def singleRequest[T: JsonValueCodec](channel: Channel, msgType: MessageType[T]): Message[T] = {

    val l = generatedMessages
      .collect {
        case Right((`channel`, m)) =>
          m.decodeAs[T] match {
            case Left(err) =>
              throw new Exception(s"Error decoding message: $err\n$m")
            case Right(m0) =>
              m0
          }
      }
      .toList

    l match {
      case Nil => throw new Exception(s"No message of type $msgType on $channel")
      case List(m) => m
      case _ => throw new Exception(s"Too many messages of type $msgType on $channel (${l.length})")
    }
  }

  // from kernel
  def singleReply[T: JsonValueCodec](channel: Channel, msgType: MessageType[T]): Message[T] = {

    val l = generatedMessages
      .collect {
        case Left((`channel`, m)) if m.header.msg_type == msgType.messageType =>
          m.decodeAs[T] match {
            case Left(err) =>
              throw new Exception(s"Error decoding message: $err\n$m")
            case Right(m0) =>
              m0
          }
      }
      .toList

    l match {
      case Nil => throw new Exception(s"No message of type $msgType on $channel")
      case List(m) => m
      case _ => throw new Exception(s"Too many messages of type $msgType on $channel (${l.length})")
    }
  }

  def generatedMessageTypes(
    channels: Set[Channel] = Set(Channel.Publish, Channel.Requests),
    filterOut: Set[String] = Set("status"),
    collapse: Set[String] = Set("stream")
  ): Seq[String] = {

    val s = generatedMessages.toStream.collect {
      case Left((c, m)) if channels(c) && !filterOut(m.header.msg_type) =>
        m.header.msg_type
    }

    def collapsing(s: scala.Stream[String]): scala.Stream[String] =
      if (s.isEmpty)
        s
      else {
        val tail =
          if (collapse(s.head))
            s.tail.dropWhile(_ == s.head)
          else
            s.tail

        s.head #:: collapsing(tail)
      }

    collapsing(s)
  }

  def executeReplies: Map[Int, String] =
    generatedMessages
      .iterator
      .collect {
        case Left((Channel.Requests, m)) if m.header.msg_type == Execute.replyType.messageType =>
          m.decodeAs[Execute.Reply] match {
            case Left(_) => Nil
            case Right(m) => Seq(m.content)
          }
      }
      .flatten
      .collect {
        case s: Execute.Reply.Success =>
          s.execution_count -> s.user_expressions.get("text/plain").fold("")(_.stringOrEmpty)
      }
      .toMap

  def executeReplyPayloads: Map[Int, Seq[RawJson]] =
    generatedMessages
      .iterator
      .collect {
        case Left((Channel.Requests, m)) if m.header.msg_type == Execute.replyType.messageType =>
          m.decodeAs[Execute.Reply] match {
            case Left(_) => Nil
            case Right(m) => Seq(m.content)
          }
      }
      .flatten
      .collect {
        case s: Execute.Reply.Success if s.payload.nonEmpty =>
          s.execution_count -> s.payload
      }
      .toMap

  def displayData: Seq[(DisplayData, Boolean)] =
    generatedMessages
      .iterator
      .collect {
        case Left((Channel.Publish, m)) if m.header.msg_type == "display_data" || m.header.msg_type == "update_display_data" =>
          val isUpdate = m.header.msg_type == "update_display_data"
          m.decodeAs[Execute.DisplayData] match {
            case Left(_) => Nil
            case Right(m) => Seq(m.content -> isUpdate)
          }
      }
      .flatten
      .toList

  def displayDataText: Seq[String] =
    generatedMessages
      .iterator
      .collect {
        case Left((Channel.Publish, m)) if m.header.msg_type == "display_data" || m.header.msg_type == "update_display_data" =>
          m.decodeAs[Execute.DisplayData] match {
            case Left(_) => Nil
            case Right(m) => Seq(m.content.data.get("text/plain").fold("")(_.stringOrEmpty))
          }
      }
      .flatten
      .toList

  def output: Seq[String] =
    generatedMessages
      .iterator
      .collect {
        case Left((Channel.Requests, m)) if m.header.msg_type == Execute.replyType.messageType =>
          m.decodeAs[Execute.Reply] match {
            case Left(_) => Nil
            case Right(m) =>
              m.content match {
                case s: Execute.Reply.Success =>
                  s.user_expressions.get("text/plain").toSeq.map(_.stringOrEmpty)
                case _ => Nil
              }
          }
        case Left((Channel.Publish, m)) if m.header.msg_type == "display_data" || m.header.msg_type == "update_display_data" =>
          m.decodeAs[Execute.DisplayData] match {
            case Left(_) => Nil
            case Right(m) => m.content.data.get("text/plain").toSeq.map(_.stringOrEmpty)
          }
      }
      .flatten
      .toList

}

object ClientStreams {

  import com.github.plokhotnyuk.jsoniter_scala.core._

  private implicit class RawJsonOps(private val rawJson: RawJson) extends AnyVal {
    def stringOrEmpty: String =
      Try(readFromArray[String](rawJson.value)).toOption.getOrElse("")
  }

  def create(
    initialMessages: Stream[IO, (Channel, RawMessage)],
    stopWhen: (Channel, Message[RawJson]) => IO[Boolean] = (_, _) => IO.pure(false),
    handler: MessageHandler = MessageHandler.discard { case _ => }
  ): ClientStreams = {

    val b = new mutable.ListBuffer[Either[(Channel, Message[RawJson]), (Channel, Message[RawJson])]]

    val poisonPill: (Channel, RawMessage) = null

    val q = {
      implicit val shift = IO.contextShift(threads.queueEc)
      Queue.bounded[IO, (Channel, RawMessage)](10).unsafeRunSync()
    }

    val sink: Pipe[IO, (Channel, RawMessage), Unit] = { s =>

      val s0 = Stream.bracket(IO.unit)(_ => q.enqueue1(poisonPill))
        .flatMap(_ => s)

      s0.evalMap {
        case (c, m) =>

          Message.parse[RawJson](m) match {
            case Left(e) =>
              IO.raiseError(new Exception(s"Error decoding message: $e"))
            case Right(m0) =>
              b += Left(c -> m0)

              val extra = stopWhen(c, m0).flatMap {
                case true =>
                  q.enqueue1(poisonPill)
                case false =>
                  IO.unit
              }

              val resp = handler.handle(c, m0) match {
                case None =>
                  IO.raiseError(new Exception(s"Unhandled message on $c of type ${m0.header.msg_type}: $m"))
                case Some(Left(e)) =>
                  IO.raiseError(new Exception("Error processing message", e))
                case Some(Right(s)) =>
                  s.evalMap(q.enqueue1).compile.drain
              }

              // bracket?
              for {
                a <- resp.attempt
                _ <- extra
                r <- IO.fromEither(a)
              } yield r
          }
      }
    }

    ClientStreams(
      initialMessages ++ q.dequeue.takeWhile(_ != poisonPill).evalMap {
        case (c, m) =>
          Message.parse[RawJson](m) match {
            case Left(e) =>
              IO.raiseError(new Exception(s"Error decoding message: $e"))
            case Right(m0) =>
              IO {
                b += Right(c -> m0)
                (c, m)
              }
          }
      },
      sink,
      b
    )
  }

}
