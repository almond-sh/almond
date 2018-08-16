package almond.channels

import java.net.ServerSocket

import almond.channels.zeromq.{ZeromqConnection, ZeromqThreads}
import almond.util.Secret
import cats.effect.IO

final case class ConnectionParameters(
  ip: String,
  transport: String,
  stdin_port: Int,
  control_port: Int,
  hb_port: Int,
  shell_port: Int,
  iopub_port: Int,
  key: Secret[String],
  signature_scheme: Option[String],
  kernel_name: Option[String] = None // jupyter seems to add this
) {

  def uri(channel: Channel): String = {

    val port = channel match {
      case Channel.Requests => shell_port
      case Channel.Control => control_port
      case Channel.Publish => iopub_port
      case Channel.Input => stdin_port
    }

    s"$transport://$ip:$port"
  }

  def heartbeatUri: String =
    s"$transport://$ip:$hb_port"

  def channels(
    bind: Boolean,
    threads: ZeromqThreads,
    identityOpt: Option[String] = None
  ): IO[ZeromqConnection] =
    ZeromqConnection(this, bind, identityOpt, threads)

}

object ConnectionParameters {

  def randomPort(): Int = {
    val s = new ServerSocket(0)
    val port = s.getLocalPort
    s.close()
    port
  }

  def randomLocal(): ConnectionParameters =
    ConnectionParameters(
      "localhost",
      "tcp",
      randomPort(),
      randomPort(),
      randomPort(),
      randomPort(),
      randomPort(),
      Secret.randomUuid(),
      Some("hmac-sha256")
    )

}
