package almond.protocol.internal

import argonaut._

object ExtraCodecs {

  implicit val jsonObjectDecoder: DecodeJson[JsonObject] =
    DecodeJson { c =>
      DecodeJson.JsonDecodeJson(c).flatMap { json =>
        json.obj match {
          case None => DecodeResult.fail("Not a JSON object", c.history)
          case Some(obj) => DecodeResult.ok(obj)
        }
      }
    }

  implicit val jsonObjectEncoder: EncodeJson[JsonObject] =
    EncodeJson.JsonEncodeJson
      .contramap(Json.jObject)

}
