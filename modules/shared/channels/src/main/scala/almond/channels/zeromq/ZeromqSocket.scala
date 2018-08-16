package almond.channels.zeromq

import almond.channels.Message
import almond.util.Secret
import cats.effect.IO
import org.zeromq.ZMQ

import scala.concurrent.ExecutionContext

trait ZeromqSocket {
  def open: IO[Unit]
  def read: IO[Option[Message]]
  def send(message: Message): IO[Unit]
  def close: IO[Unit]

  def channel: ZMQ.Socket
}

object ZeromqSocket {

  /**
    *
    * @param ec: [[ExecutionContext]] to run I/O operations on - *should be single threaded*
    */
  def apply(
    ec: ExecutionContext,
    socketType: Int,
    bind: Boolean,
    uri: String,
    identityOpt: Option[Array[Byte]],
    subscribeOpt: Option[Array[Byte]],
    context: ZMQ.Context,
    key: Secret[String],
    algorithm: String
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
      algorithm
    )

}
