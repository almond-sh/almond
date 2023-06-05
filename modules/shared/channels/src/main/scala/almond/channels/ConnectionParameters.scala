package almond.channels

import java.net.ServerSocket

import almond.channels.zeromq.{ZeromqConnection, ZeromqThreads}
import almond.logger.LoggerContext
import almond.util.Secret
import cats.effect.IO

import scala.concurrent.duration.Duration

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
      case Channel.Control  => control_port
      case Channel.Publish  => iopub_port
      case Channel.Input    => stdin_port
    }

    s"$transport://$ip:$port"
  }

  def heartbeatUri: String =
    s"$transport://$ip:$hb_port"

  def channels(
    bind: Boolean,
    threads: ZeromqThreads,
    lingerPeriod: Option[Duration],
    logCtx: LoggerContext,
    identityOpt: Option[String] = None
  ): IO[ZeromqConnection] =
    ZeromqConnection(this, bind, identityOpt, threads, lingerPeriod, logCtx)

}

object ConnectionParameters {

  def randomPorts(): (Int, Int, Int, Int, Int) = {
    val s0    = new ServerSocket(0)
    val s1    = new ServerSocket(0)
    val s2    = new ServerSocket(0)
    val s3    = new ServerSocket(0)
    val s4    = new ServerSocket(0)
    val port0 = s0.getLocalPort
    val port1 = s1.getLocalPort
    val port2 = s2.getLocalPort
    val port3 = s3.getLocalPort
    val port4 = s4.getLocalPort
    s0.close()
    s1.close()
    s2.close()
    s3.close()
    s4.close()
    (port0, port1, port2, port3, port4)
  }

  def randomLocal(): ConnectionParameters = {
    val (stdin, control, hb, shell, iopub) = randomPorts()
    ConnectionParameters(
      "localhost",
      "tcp",
      stdin,
      control,
      hb,
      shell,
      iopub,
      Secret.randomUuid(),
      Some("hmac-sha256")
    )
  }

}
