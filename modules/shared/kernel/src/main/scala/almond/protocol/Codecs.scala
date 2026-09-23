package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

object Codecs {

  implicit val unitCodec: JsonValueCodec[Unit] = {
    final case class Empty()
    val empty      = Empty()
    val emptyCodec = JsonCodecMaker.make[Empty]

    new JsonValueCodec[Unit] {
      def decodeValue(in: JsonReader, default: Unit) = emptyCodec.decodeValue(in, empty)
      def encodeValue(x: Unit, out: JsonWriter)      = emptyCodec.encodeValue(empty, out)
      def nullValue                                  = ()
    }
  }

  implicit val stringCodec: JsonValueCodec[String] =
    JsonCodecMaker.make

}
