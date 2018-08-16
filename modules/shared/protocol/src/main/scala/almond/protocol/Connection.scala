package almond.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import almond.channels.ConnectionParameters
import almond.util.Secret
import argonaut._
import argonaut.Argonaut._
import argonaut.ArgonautShapeless._
import cats.effect.IO

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

  def fromPath(path: Path): IO[Connection] =
    for {
      b <- IO(Files.readAllBytes(path))
      s = new String(b, UTF_8)
      c <- {
        s.decodeEither[Connection] match {
          case Left(e) =>
            IO.raiseError(new Exception(s"Error parsing $path: $e"))
          case Right(c) =>
            IO.pure(c)
        }
      }
    } yield c


  private implicit val secretStringDecoder: DecodeJson[Secret[String]] =
    DecodeJson.StringDecodeJson.map(Secret(_))
  private implicit val secretStringEncoder: EncodeJson[Secret[String]] =
    EncodeJson.StringEncodeJson.contramap(_.value)

  implicit val decoder = DecodeJson.of[Connection]
  implicit val encoder = EncodeJson.of[Connection]

}
