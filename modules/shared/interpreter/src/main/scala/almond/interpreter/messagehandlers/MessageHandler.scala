package almond.interpreter.messagehandlers

import fs2.async.boundedQueue
import fs2.async.mutable.Queue
import fs2.Stream
import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.Message
import almond.logger.{Logger, LoggerContext}
import almond.protocol.{MessageType, Status}
import argonaut.{DecodeJson, Json}
import cats.effect.IO

import scala.concurrent.ExecutionContext

/**
  * Wraps a partial function, able to handle some [[Message]]s arriving via a given [[Channel]].
  *
  * If a [[Message]] is handled, one can get either a [[Throwable]], meaning the message was malformed, or
  * a [[Stream]] of [[RawMessage]] to be sent on a given [[Channel]] as answer.
  */
final case class MessageHandler(
  handler: PartialFunction[
    (Channel, Message[Json]),
    Either[Throwable, Stream[IO, (Channel, RawMessage)]]
  ]
) {

  def orElse(other: MessageHandler*): MessageHandler =
    MessageHandler(
      other.foldLeft(handler)((acc, h) => acc.orElse(h.handler))
    )

  private lazy val lifted = handler.lift

  def handle(channel: Channel, message: Message[Json]): Option[Either[Throwable, Stream[IO, (Channel, RawMessage)]]] =
    lifted((channel, message))

  def handle(channel: Channel, message: RawMessage): Option[Either[Throwable, Stream[IO, (Channel, RawMessage)]]] =
    Message.parse[Json](message) match {
      case Left(error) =>
        Some(Left(new Exception(s"Error decoding message: $error")))
      case Right(message0) =>
        handle(channel, message0)
    }

  def handleOrLogError(
    channel: Channel,
    message: RawMessage,
    log: Logger
  ): Option[Stream[IO, (Channel, RawMessage)]] =
    handle(channel, message).map {
      case Left(e) =>
        log.error(s"Ignoring error decoding message\n$message", e)
        Stream.empty
      case Right(s) => s
    }

}

object MessageHandler {

  def empty: MessageHandler =
    MessageHandler(PartialFunction.empty)

  /**
    * Constructs a [[MessageHandler]], able to handle a [[Message]] of a single type from a single [[Channel]].
    *
    * The passed handler should return a [[Stream]] of messages as a response to the incoming message.
    *
    * @param channel: [[Channel]] this [[MessageHandler]] handles [[Message]]s from
    * @param messageType: type of the [[Message]]s this [[MessageHandler]] handles
    */
  def apply[T: DecodeJson](
    channel: Channel,
    messageType: MessageType[T]
  )(
    handler: Message[T] => Stream[IO, (Channel, RawMessage)]
  ): MessageHandler =
    MessageHandler {
      case (`channel`, message) if message.messageType == messageType =>
        message
          .decodeAs[T]
          .left
          .map(e => new Exception(s"Error decoding message: $e"))
          .right
          .map(handler)
    }

  /**
    * Constructs a [[MessageHandler]], that reports the kernel as busy while a [[Message]] is being processed.
    *
    * Messages can be pushed to the queue passed to the handler, while the [[IO]] it returns is being run.
    *
    * @param channel: [[Channel]] this [[MessageHandler]] handles [[Message]]s from
    * @param messageType: type of the [[Message]]s this [[MessageHandler]] handles
    */
  def blocking[T: DecodeJson](
    channel: Channel,
    messageType: MessageType[T],
    queueEc: ExecutionContext,
    logCtx: LoggerContext
  )(
    handler: (Message[T], Queue[IO, (Channel, RawMessage)]) => IO[Unit]
  ): MessageHandler =
    MessageHandler(channel, messageType) { message =>
      blockingTaskStream(message, queueEc, logCtx) { queue =>
        handler(message, queue)
      }
    }

  private def blockingTaskStream(
    currentMessage: Message[_],
    queueEc: ExecutionContext,
    logCtx: LoggerContext
  )(
    run: Queue[IO, (Channel, RawMessage)] => IO[Unit]
  ): Stream[IO, (Channel, RawMessage)] = {

    val log = logCtx(getClass)

    /*
     * While the task returned by run is being evaluated, messages can be pushed to the queue it is passed.
     */

    def status(queue: Queue[IO, (Channel, RawMessage)], state: Status): IO[Unit] =
      currentMessage
        .publish(Status.messageType, state)
        .enqueueOn(Channel.Publish, queue)

    val poisonPill: (Channel, RawMessage) = null // a bit meh

    val task = for {
      queue <- {
        implicit val S = queueEc
        boundedQueue[IO, (Channel, RawMessage)](40) // FIXME sizing?
      }
      main = run(queue)
      _ <- {
        val t = for {
          _ <- status(queue, Status.busy)
          _ <- main.attempt.map { a =>
            a.left.foreach { e =>
              log.error(s"Error while processing ${currentMessage.header.msg_type} message", e)
            }
          }
          _ <- status(queue, Status.idle)
          _ <- queue.enqueue1(poisonPill)
        } yield ()

        t.attempt.flatMap {
          case Left(e) =>
            log.error(s"Internal error while processing ${currentMessage.header.msg_type} message", e)
            IO.raiseError(e)
          case Right(()) =>
            IO.unit
        }.start
      }
    } yield queue.dequeue.takeWhile(_ != poisonPill)

    Stream.eval(task)
      .flatMap(x => x)
  }

  def discard(pf: PartialFunction[(Channel, Message[Json]), Unit]): MessageHandler =
    MessageHandler {
      pf.andThen(_ => Right(Stream.empty))
    }

}
