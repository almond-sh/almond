package almond.protocol

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

final case class Connection(
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
  def connectionParameters =
    ConnectionParameters(
      ip,
      transport,
      stdin_port,
      control_port,
      hb_port,
      shell_port,
      iopub_port,
      key,
      signature_scheme,
      kernel_name
    )
}

object Connection {

  def fromParams(params: ConnectionParameters): Connection =
    Connection(
      params.ip,
      params.transport,
      params.stdin_port,
      params.control_port,
      params.hb_port,
      params.shell_port,
      params.iopub_port,
      params.key,
      params.signature_scheme,
      params.kernel_name
    )

  implicit val codec: JsonValueCodec[Connection] = {

    final case class RawConnection(
      ip: String,
      transport: String,
      stdin_port: Int,
      control_port: Int,
      hb_port: Int,
      shell_port: Int,
      iopub_port: Int,
      key: String,
      signature_scheme: Option[String],
      kernel_name: Option[String] = None
    )

    implicit class RawConnectionOps(private val rawConn: RawConnection) {
      def connection: Connection =
        if (rawConn == null) null
        else
          Connection(
            rawConn.ip,
            rawConn.transport,
            rawConn.stdin_port,
            rawConn.control_port,
            rawConn.hb_port,
            rawConn.shell_port,
            rawConn.iopub_port,
            Secret(rawConn.key),
            rawConn.signature_scheme,
            rawConn.kernel_name
          )
    }

    def rawConnection(conn: Connection): RawConnection =
      if (conn == null) null
      else
        RawConnection(
          conn.ip,
          conn.transport,
          conn.stdin_port,
          conn.control_port,
          conn.hb_port,
          conn.shell_port,
          conn.iopub_port,
          conn.key.value,
          conn.signature_scheme,
          conn.kernel_name
        )

    val underlying: JsonValueCodec[RawConnection] =
      JsonCodecMaker.make

    new JsonValueCodec[Connection] {
      def decodeValue(in: JsonReader, default: Connection): Connection =
        underlying.decodeValue(in, rawConnection(default)).connection
      def encodeValue(x: Connection, out: JsonWriter): Unit =
        underlying.encodeValue(rawConnection(x), out)
      def nullValue: Connection =
        underlying.nullValue.connection
    }
  }

  def fromPath(path: Path): IO[Connection] =
    for {
      b <- IO(Files.readAllBytes(path))
      c <- {
        Try(readFromArray(b)(codec)).toEither match {
          case Left(e) =>
            IO.raiseError(new Exception(s"Error parsing $path", e))
          case Right(c) =>
            IO.pure(c)
        }
      }
    } yield c

}
