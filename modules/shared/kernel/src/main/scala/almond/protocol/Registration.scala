package almond.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import almond.channels.ConnectionParameters
import almond.util.Secret

import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  readFromArray
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import scala.util.Try

/** Content of a registration file, passed to kernels in lieu of a connection file when using the
  * kernel startup handshake (protocol 5.6)
  *
  * The kernel is expected to bind its channels to ports of its choice, then send those ports to the
  * registration socket at `transport://registration_ip:registration_port`.
  *
  * See https://jupyter-client.readthedocs.io/en/latest/kernels.html#registration-file-format and
  * https://jupyter-client.readthedocs.io/en/latest/messaging.html#kernel-startup-handshake.
  */
final case class Registration(
  kernel_id: String,
  transport: String,
  registration_ip: String,
  registration_port: Int,
  key: Secret[String],
  signature_scheme: Option[String],
  // not in the spec, but written by some implementations (xeus) - IP to bind the kernel channels to
  ip: Option[String] = None,
  kernel_name: Option[String] = None
) {

  def registrationUri: String =
    s"$transport://$registration_ip:$registration_port"

  /** Connection parameters with zero-d ports, meant to be bound to random ports */
  def connectionParameters: ConnectionParameters =
    ConnectionParameters(
      ip = ip.getOrElse(registration_ip),
      transport = transport,
      stdin_port = 0,
      control_port = 0,
      hb_port = 0,
      shell_port = 0,
      iopub_port = 0,
      key = key,
      signature_scheme = signature_scheme,
      kernel_name = kernel_name
    )
}

object Registration {

  /** Content of the message sent by the kernel to the registration socket
    *
    * Ports are sent as strings, as mandated by the spec.
    */
  final case class ConnectionInfo(
    kernel_id: String,
    control_port: String,
    shell_port: String,
    stdin_port: String,
    iopub_port: String,
    hb_port: String
  )

  object ConnectionInfo {
    def apply(kernelId: String, params: ConnectionParameters): ConnectionInfo =
      ConnectionInfo(
        kernel_id = kernelId,
        control_port = params.control_port.toString,
        shell_port = params.shell_port.toString,
        stdin_port = params.stdin_port.toString,
        iopub_port = params.iopub_port.toString,
        hb_port = params.hb_port.toString
      )

    implicit val codec: JsonValueCodec[ConnectionInfo] =
      JsonCodecMaker.make
  }

  implicit val codec: JsonValueCodec[Registration] = {

    // registration_port is an int in the spec examples, but xeus reads and writes it as a string
    final case class RawRegistration(
      kernel_id: String,
      transport: String,
      registration_ip: String,
      registration_port: RawJson,
      key: String,
      signature_scheme: Option[String],
      ip: Option[String] = None,
      kernel_name: Option[String] = None
    )

    def parsePort(json: RawJson): Int = {
      val s = new String(json.value, UTF_8).trim
      val s0 =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1)
        else s
      s0.toInt
    }

    val underlying: JsonValueCodec[RawRegistration] =
      JsonCodecMaker.make

    new JsonValueCodec[Registration] {
      def decodeValue(in: JsonReader, default: Registration): Registration = {
        val raw = underlying.decodeValue(in, underlying.nullValue)
        if (raw == null) default
        else {
          val port = Try(parsePort(raw.registration_port)).getOrElse {
            in.decodeError(s"Malformed registration_port: ${raw.registration_port}")
          }
          Registration(
            raw.kernel_id,
            raw.transport,
            raw.registration_ip,
            port,
            Secret(raw.key),
            raw.signature_scheme,
            raw.ip,
            raw.kernel_name
          )
        }
      }
      def encodeValue(x: Registration, out: JsonWriter): Unit =
        underlying.encodeValue(
          RawRegistration(
            x.kernel_id,
            x.transport,
            x.registration_ip,
            RawJson(x.registration_port.toString.getBytes(UTF_8)),
            x.key.value,
            x.signature_scheme,
            x.ip,
            x.kernel_name
          ),
          out
        )
      def nullValue: Registration = null
    }
  }

  private final case class Probe(
    registration_ip: Option[String] = None,
    registration_port: Option[RawJson] = None
  )

  private implicit val probeCodec: JsonValueCodec[Probe] =
    JsonCodecMaker.make

  /** Whether the passed JSON content is that of a registration file (rather than a connection file)
    */
  def isRegistration(content: Array[Byte]): Boolean =
    Try(readFromArray(content)(probeCodec)).toOption.exists { probe =>
      probe.registration_ip.exists(_.nonEmpty) || probe.registration_port.nonEmpty
    }

  def parse(content: Array[Byte], source: => String): IO[Registration] =
    Try(readFromArray(content)(codec)).toEither match {
      case Left(e)  => IO.raiseError(new Exception(s"Error parsing $source", e))
      case Right(r) => IO.pure(r)
    }

  /** Reads the passed file, and parses it as a registration file if it is one */
  def fromPathIfRegistration(path: Path): IO[Option[Registration]] =
    for {
      b <- IO(Files.readAllBytes(path))
      r <-
        if (isRegistration(b)) parse(b, path.toString).map(Some(_))
        else IO.pure(None)
    } yield r

}
