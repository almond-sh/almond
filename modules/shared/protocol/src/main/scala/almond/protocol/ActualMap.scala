package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

// Wrapper around Map[String, T] that always serializes as a JSON object,
// working around a jsoniter-scala Scala 3 macro issue that incorrectly
// serializes Map[String, String] as an array of pairs (see issue #1499).
final case class ActualMap[T](map: Map[String, T])

object ActualMap {
  implicit val stringCodec: JsonValueCodec[ActualMap[String]] =
    new JsonValueCodec[ActualMap[String]] {
      def nullValue: ActualMap[String] = ActualMap(Map.empty)
      def decodeValue(
        in: JsonReader,
        default: ActualMap[String]
      ): ActualMap[String] = {
        var result = Map.empty[String, String]
        if (in.isNextToken('{'.toByte)) {
          if (!in.isNextToken('}'.toByte)) {
            in.rollbackToken()
            var hasMore = true
            while (hasMore) {
              val k = in.readKeyAsString()
              val v = in.readString(null)
              result = result + (k -> v)
              hasMore = in.isNextToken(','.toByte)
            }
            if (!in.isCurrentToken('}'.toByte)) in.objectEndOrCommaError()
          }
        }
        else result = in.readNullOrTokenError(Map.empty, '{'.toByte)
        ActualMap(result)
      }
      def encodeValue(x: ActualMap[String], out: JsonWriter): Unit = {
        out.writeObjectStart()
        x.map.toSeq.sortBy(_._1).foreach { case (k, v) =>
          out.writeKey(k)
          out.writeVal(v)
        }
        out.writeObjectEnd()
      }
    }
}
