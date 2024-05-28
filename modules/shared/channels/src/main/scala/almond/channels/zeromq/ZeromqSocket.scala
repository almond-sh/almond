package almond.channels.zeromq

import almond.channels.Message
import almond.logger.LoggerContext
import almond.util.Secret
import cats.effect.IO
import org.zeromq.{SocketType, ZMQ}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

trait ZeromqSocket {
  def open: IO[Unit]
  def read: IO[Option[Message]]
  def send(message: Message): IO[Unit]
  def close(lingerDuration: Duration): IO[Unit]

  def channel: ZMQ.Socket
}

object ZeromqSocket {

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
    logCtx: LoggerContext
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
      logCtx
    )

}
