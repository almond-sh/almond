package almond.interpreter.util

import java.nio.charset.StandardCharsets

import almond.interpreter.api.DisplayData
import almond.protocol.RawJson
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

import scala.language.implicitConversions
import scala.util.Try

final class DisplayDataOps(val displayData: DisplayData) extends AnyVal {

  import DisplayDataOps._

  def jsonData: Map[String, RawJson] =
    displayData
      .data
      .map {
        case (mimeType, content) =>
          val json =
            if (isJsonMimeType(mimeType) && isJson(content))
              content.getBytes(StandardCharsets.UTF_8)
            else
              asJsonString(content)

          mimeType -> RawJson(json)
      }

  def jsonMetadata: Map[String, RawJson] =
    displayData
      .metadata
      .map {
        case (key, content) =>
          val json = if (isJson(content))
            content.getBytes(StandardCharsets.UTF_8)
          else
            asJsonString(content)

          key -> RawJson(json)
      }
}

object DisplayDataOps {

  private implicit val stringCodec: JsonValueCodec[String] =
    JsonCodecMaker.make(CodecMakerConfig)

  // FIXME Define a JsonValueCodec[Unit]
  private final case class Empty()
  private implicit val unitCodec: JsonValueCodec[Empty] =
    JsonCodecMaker.make(CodecMakerConfig)

  private def isJson(s: String): Boolean =
    Try(readFromString(s)(unitCodec)).isSuccess

  private def asJsonString(s: String): Array[Byte] =
    writeToArray(s)(stringCodec)

  private def isJsonMimeType(mimeType: String): Boolean =
    mimeType == "application/json" ||
      (mimeType.startsWith("application/") && mimeType.endsWith("+json"))

  implicit def toDisplayDataOps(displayData: DisplayData): DisplayDataOps =
    new DisplayDataOps(displayData)

}
