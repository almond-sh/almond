package almond.channels

import cats.effect.IO
import fs2.{Pipe, Stream}

import scala.concurrent.duration.{Duration, DurationInt}

abstract class Connection {

  /** Open the channels.
    *
    * Must be run prior to [[send]], [[tryRead]], [[stream]], [[sink]].
    */
  def open: IO[Unit]

  /** Send a message through a channel.
    *
    * @param channel:
    *   channel to send the message through
    * @param message:
    *   message to send
    */
  def send(channel: Channel, message: Message): IO[Unit]

  /** Try reading a single message from some possible channels.
    *
    * @param channels:
    *   channels the message can be read from
    * @param pollingDelay:
    *   maximum amount of time to wait until a message can be read
    * @return
    *   a [[Message]], if any, along with the [[Channel]] it was read from, wrapped in
    *   [[scala.Some]]; else [[scala.None]]
    */
  def tryRead(channels: Seq[Channel], pollingDelay: Duration): IO[Option[(Channel, Message)]]

  /** Close the channels.
    *
    * Can be run multiple times. Only the first call will actually close the channels.
    */
  def close(partial: Boolean, lingerDuration: Duration): IO[Unit]

  /** Try to read a message from the specified [[Channel]].
    *
    * @param channel:
    *   [[Channel]] to read the message from
    * @param pollingDelay:
    *   maximum amount of time to wait until a message can be read
    * @return
    *   a [[Message]], if any, wrapped in [[scala.Some]], else [[scala.None]]
    */
  final def tryRead(channel: Channel, pollingDelay: Duration): IO[Option[Message]] =
    tryRead(Seq(channel), pollingDelay).map(_.map {
      case (channel0, message) =>
        assert(channel == channel0)
        message
    })

  /** [[Stream]] of [[Message]]s from the specified [[Channel]]s.
    *
    * @param channels:
    *   channels to get [[Message]]s from
    * @param pollingDelay:
    *   amount of time to wait until a message can be read (passed to [[tryRead]]), before trying
    *   again
    */
  final def stream(
    channels: Seq[Channel] = Channel.channels,
    pollingDelay: Duration = 1.second
  ): Stream[IO, (Channel, Message)] =
    Stream
      .repeatEval(tryRead(channels, pollingDelay))
      .flatMap(t => Stream(t.toList: _*))

  /** Sink to send [[Message]]s to any [[Channel]].
    */
  final def sink: Pipe[IO, (Channel, Message), Unit] =
    _.evalMap((send _).tupled)

  final def autoCloseSink(
    partial: Boolean,
    lingerDuration: Duration
  ): Pipe[IO, (Channel, Message), Unit] =
    s => Stream.bracket(IO.unit)(_ => close(partial, lingerDuration)).flatMap(_ => sink(s))

}
