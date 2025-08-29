package almond.interpreter.util

import java.nio.charset.StandardCharsets

import almond.interpreter.api.DisplayData
import almond.protocol.Codecs.{stringCodec, unitCodec}
import almond.protocol.RawJson

import scala.collection.immutable.ListMap
import scala.language.implicitConversions
import scala.util.Try

final class DisplayDataOps(val displayData: DisplayData) extends AnyVal {

  import DisplayDataOps._

  def jsonData: ListMap[String, RawJson] =
    displayData
      .detailedData
      .map {
        case (mimeType, content: DisplayData.Value.String)
            if isJsonMimeType(mimeType) && isJson(content.value) =>
          mimeType -> RawJson(content.value.getBytes(StandardCharsets.UTF_8))
        case (mimeType, content: DisplayData.Value.String) =>
          mimeType -> RawJson(asJsonString(content.value))
        case (mimeType, content: DisplayData.Value.Json) =>
          mimeType -> RawJson(content.value.getBytes(StandardCharsets.UTF_8))
      }

  def jsonMetadata: ListMap[String, RawJson] =
    displayData
      .detailedMetadata
      .map {
        case (key, content: DisplayData.Value.String) =>
          key -> RawJson(asJsonString(content.value))
        case (key, content: DisplayData.Value.Json) =>
          key -> RawJson(content.value.getBytes(StandardCharsets.UTF_8))
      }
}

object DisplayDataOps {

  import com.github.plokhotnyuk.jsoniter_scala.core._

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
