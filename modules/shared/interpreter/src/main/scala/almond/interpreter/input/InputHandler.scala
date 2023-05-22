package almond.interpreter.input

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.logger.LoggerContext
import almond.protocol.Input
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.Success

final class InputHandler(
  futureEc: ExecutionContext,
  logCtx: LoggerContext
) {

  private val log = logCtx(getClass)

  // this implem is possibly a bit overkill for now, as there's supposed to be at most one input request at a time…

  private val ongoing = new ConcurrentHashMap[String, Promise[String]]

  def inputManager(
    parentMessage: Message[_],
    send: (Channel, RawMessage) => IO[Unit]
  ): InputManager =
    new InputManager {
      private val list  = new ConcurrentHashMap[String, Unit]
      private var done0 = false
      def done(): Unit = {
        for ((id, ()) <- list.asScala.toSeq; p <- Option(ongoing.remove(id)))
          if (!p.isCompleted)
            p.failure(new InputManager.NoMoreInputException)
        done0 = true
      }
      def readInput(prompt: String, password: Boolean) =
        if (done0)
          Future.failed(new InputManager.NoMoreInputException)
        else {

          val id = UUID.randomUUID().toString
          val p  = Promise[String]() // timeout that if not completed in imparted time?

          val msg = parentMessage.publish(
            Input.requestType,
            Input.Request(prompt, password),
            ident = Some("stdin")
          ).asRawMessage

          list.put(id, ())
          ongoing.put(id, p)

          {
            implicit val ec = futureEc
            for {
              _ <- send(Channel.Input, msg).unsafeToFuture()(IORuntime.global)
              s <- p.future
            } yield s
          }
        }
    }

  def messageHandler: MessageHandler =
    MessageHandler(Channel.Input, Input.replyType) { msg =>

      def resp(id: String, p: Promise[String]) =
        Stream.eval(
          IO {
            ongoing.remove(id)
            p.complete(Success(msg.content.value))
          }
        ).drain

      msg.parent_header match {
        case None =>
          // I wish the client would send the header back via parent_header…

          val m = ongoing.asScala
          if (m.size == 1) {
            log.warn("Input reply has no parent header")
            val (id, p) = m.head
            resp(id, p)
          }
          else {
            log.warn("Unhandled input reply (missing parent header)")
            Stream.empty
          }

        case Some(parentHeader) =>
          Option(ongoing.get(parentHeader.msg_id)) match {
            case None =>
              log.warn(
                s"Unhandled input reply (unrecognized parent message id ${parentHeader.msg_id})"
              )
              Stream.empty
            case Some(p) =>
              resp(parentHeader.msg_id, p)
          }
      }
    }

}
