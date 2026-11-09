package almond.channels.relay

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import java.nio.file.{Files, Path}

/** Tells a kernel started by the launcher where to connect to the launcher, and how to authenticate
  * itself
  *
  * @param port
  *   port the launcher listens on, on the loopback interface
  * @param key
  *   secret to send upon connection, hex-encoded
  */
final case class RelayFile(
  port: Int,
  key: String
) {
  def keyBytes: Array[Byte] =
    key.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  def asJson: Array[Byte] =
    writeToArray(this)(RelayFile.codec)
}

object RelayFile {
  def apply(port: Int, key: Array[Byte]): RelayFile =
    RelayFile(port, key.map(b => f"$b%02x").mkString)

  def read(path: Path): RelayFile =
    readFromArray(Files.readAllBytes(path))(codec)

  implicit val codec: JsonValueCodec[RelayFile] =
    JsonCodecMaker.make
}
