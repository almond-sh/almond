package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

// Wrapper around Map[String, T] that always serializes as a JSON object,
// working around a jsoniter-scala Scala 3 macro issue that incorrectly
// serializes Map[String, String] as an array of pairs (see issue #1499).
final case class ActualMap[T](map: Map[String, T])

object ActualMap {
  implicit def codec[T: JsonValueCodec]: JsonValueCodec[ActualMap[T]] = {
    val tCodec = implicitly[JsonValueCodec[T]]
    new JsonValueCodec[ActualMap[T]] {
      def nullValue: ActualMap[T] = ActualMap(Map.empty)
      def decodeValue(
        in: JsonReader,
        default: ActualMap[T]
      ): ActualMap[T] = {
        var result = Map.empty[String, T]
        if (in.isNextToken('{'.toByte)) {
          if (!in.isNextToken('}'.toByte)) {
            in.rollbackToken()
            var hasMore = true
            while (hasMore) {
              val k = in.readKeyAsString()
              val v = tCodec.decodeValue(in, tCodec.nullValue)
              result = result + (k -> v)
              hasMore = in.isNextToken(','.toByte)
            }
            if (!in.isCurrentToken('}'.toByte)) in.objectEndOrCommaError()
          }
        }
        else result = in.readNullOrTokenError(Map.empty[String, T], '{'.toByte)
        ActualMap(result)
      }
      def encodeValue(x: ActualMap[T], out: JsonWriter): Unit = {
        out.writeObjectStart()
        x.map.toSeq.sortBy(_._1).foreach { case (k, v) =>
          out.writeKey(k)
          tCodec.encodeValue(v, out)
        }
        out.writeObjectEnd()
      }
    }
  }
}
