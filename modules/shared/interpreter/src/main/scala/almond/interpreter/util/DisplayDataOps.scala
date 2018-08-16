package almond.interpreter.util

import almond.interpreter.api.DisplayData
import argonaut.Json
import argonaut.Parse.{parse => parseJson}

import scala.language.implicitConversions

final class DisplayDataOps(val displayData: DisplayData) extends AnyVal {

  def jsonData: Map[String, Json] =
    displayData
      .data
      .map {
        case (mimeType, content) =>
          val json =
            if (DisplayDataOps.isJsonMimeType(mimeType))
              parseJson(content)
                .right
                .toOption
                .getOrElse(Json.jString(content))
            else
              Json.jString(content)

          mimeType -> json
      }

  def jsonMetadata: Map[String, Json] =
    displayData
      .metadata
      .map {
        case (key, content) =>
          val json = parseJson(content)
            .right
            .toOption
            .getOrElse(Json.jString(content))

          key -> json
      }
}

object DisplayDataOps {

  private def isJsonMimeType(mimeType: String): Boolean =
    mimeType == "application/json" ||
      (mimeType.startsWith("application/") && mimeType.endsWith("+json"))

  implicit def toDisplayDataOps(displayData: DisplayData): DisplayDataOps =
    new DisplayDataOps(displayData)

}
