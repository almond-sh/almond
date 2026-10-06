package almond.channels.zeromq

import almond.channels.Message
import almond.logger.LoggerContext
import almond.util.Secret
import cats.effect.IO
import org.zeromq.{SocketType, ZMQ}

import java.nio.channels.SelectableChannel

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

trait ZeromqSocket {

  /** @return
    *   Randomly chosen port, if the port was picked this way
    */
  def open: IO[Option[Int]]
  def read: IO[Option[Message]]

  /** Reads a subscription event, for XPUB sockets
    *
    * @return
    *   the event, if a valid one could be read
    */
  def readSubscriptionEvent: IO[Option[ZeromqSocket.SubscriptionEvent]]
  def send(message: Message): IO[Unit]

  /** Sends a message, then checks whether input is pending, like [[hasPendingInput]] does
    *
    * Sending makes the socket process its pending commands, which may make its file descriptor (see
    * [[fd]]) non-readable while input is pending.
    */
  def sendAndCheckInput(message: Message): IO[Boolean]

  def close(lingerDuration: Duration): IO[Unit]

  /** Whether a message (or a subscription event, for XPUB sockets) can be read
    *
    * This processes the commands pending for the socket, which may make its file descriptor (see
    * [[fd]]) non-readable.
    *
    * Fails with a [[java.nio.channels.ClosedChannelException]] if the socket is closed.
    */
  def hasPendingInput: IO[Boolean]

  /** File descriptor that becomes readable when commands are pending for the socket (ZMQ_FD)
    *
    * Readiness of this file descriptor doesn't mean a message can be read, use [[hasPendingInput]]
    * to check that. It can be waited for from any thread.
    */
  def fd: SelectableChannel

  /** Underlying JeroMQ socket
    *
    * JeroMQ sockets aren't thread-safe, so this should only be used from the [[ExecutionContext]]
    * that this [[ZeromqSocket]] runs its I/O operations on.
    */
  def channel: ZMQ.Socket
}

object ZeromqSocket {

  /** Subscription or unsubscription of a SUB socket, as received by an XPUB socket
    *
    * @param subscribe
    *   whether this is a subscription (true) or an unsubscription (false)
    * @param topic
    *   the topic being (un-)subscribed to - empty for all topics
    */
  final case class SubscriptionEvent(subscribe: Boolean, topic: Seq[Byte])

  /** @param ec:
    *   [[ExecutionContext]] to run I/O operations on - *should be single threaded*
    */
  def apply(
    ec: ExecutionContext,
    socketType: SocketType,
    bind: Boolean,
    uri: String,
    identityOpt: Option[Array[Byte]],
    subscribeOpt: Option[Array[Byte]],
    context: ZMQ.Context,
    key: Secret[String],
    algorithm: String,
    lingerPeriod: Option[Duration],
    logCtx: LoggerContext,
    bindToRandomPort: Boolean
  ): ZeromqSocket =
    new ZeromqSocketImpl(
      ec,
      socketType,
      bind,
      uri,
      identityOpt,
      subscribeOpt,
      context,
      key,
      algorithm,
      lingerPeriod,
      logCtx,
      bindToRandomPort = bindToRandomPort
    )

  @deprecated("Use the override accepting bindToRandomPort", "0.14.2")
  def apply(
    ec: ExecutionContext,
    socketType: SocketType,
    bind: Boolean,
    uri: String,
    identityOpt: Option[Array[Byte]],
    subscribeOpt: Option[Array[Byte]],
    context: ZMQ.Context,
    key: Secret[String],
    algorithm: String,
    lingerPeriod: Option[Duration],
    logCtx: LoggerContext
  ): ZeromqSocket =
    apply(
      ec,
      socketType,
      bind,
      uri,
      identityOpt,
      subscribeOpt,
      context,
      key,
      algorithm,
      lingerPeriod,
      logCtx,
      bindToRandomPort = true
    )
}
