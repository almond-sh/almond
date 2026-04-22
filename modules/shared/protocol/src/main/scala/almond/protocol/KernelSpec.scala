package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  readFromArray
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

// See http://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs

final case class KernelSpec(
  argv: List[String],
  display_name: String,
  language: String,
  interrupt_mode: Option[String] = None,
  env: Map[String, String] = Map.empty,
  metadata: Option[RawJson] = None
)

object KernelSpec {
  implicit val codec: JsonValueCodec[KernelSpec] = {
    // Use an intermediate 'Raw' class where env is stored as raw JSON bytes.
    // This works around a jsoniter-scala Scala 3 macro issue that incorrectly
    // serializes Map[String, String] as an array of pairs instead of a JSON
    // object, breaking Jupyter kernel installation (see issue #1499).
    final case class Raw(
      argv: List[String],
      display_name: String,
      language: String,
      interrupt_mode: Option[String] = None,
      env: RawJson = RawJson.emptyObj,
      metadata: Option[RawJson] = None
    )
    val rawCodec: JsonValueCodec[Raw] = JsonCodecMaker.make

    val listCodec: JsonValueCodec[List[String]] = JsonCodecMaker.make

    // Codec for Map[String, String] that always reads and writes as a JSON object.
    val mapCodec: JsonValueCodec[Map[String, String]] =
      new JsonValueCodec[Map[String, String]] {
        def nullValue: Map[String, String] = Map.empty
        def decodeValue(
          in: JsonReader,
          default: Map[String, String]
        ): Map[String, String] = {
          var result = Map.empty[String, String]
          if (in.isNextToken('{'.toByte)) {
            if (!in.isNextToken('}'.toByte)) {
              in.rollbackToken()
              do {
                val k = in.readKeyAsString()
                val v = in.readString(null)
                result = result + (k -> v)
              } while (in.isNextToken(','.toByte))
              if (!in.isCurrentToken('}'.toByte)) in.objectEndOrCommaError()
            }
          }
          else result = in.readNullOrTokenError(Map.empty, '{'.toByte)
          result
        }
        def encodeValue(x: Map[String, String], out: JsonWriter): Unit = {
          out.writeObjectStart()
          x.toSeq.sortBy(_._1).foreach { case (k, v) =>
            out.writeKey(k)
            out.writeVal(v)
          }
          out.writeObjectEnd()
        }
      }

    new JsonValueCodec[KernelSpec] {
      def nullValue: KernelSpec = null
      def decodeValue(in: JsonReader, default: KernelSpec): KernelSpec = {
        val raw = rawCodec.decodeValue(in, rawCodec.nullValue)
        if (raw == null) null
        else {
          val env = readFromArray(raw.env.value)(mapCodec)
          KernelSpec(raw.argv, raw.display_name, raw.language, raw.interrupt_mode, env, raw.metadata)
        }
      }
      def encodeValue(x: KernelSpec, out: JsonWriter): Unit = {
        out.writeObjectStart()
        out.writeKey("argv")
        listCodec.encodeValue(x.argv, out)
        out.writeKey("display_name")
        out.writeVal(x.display_name)
        out.writeKey("language")
        out.writeVal(x.language)
        x.interrupt_mode.foreach { mode =>
          out.writeKey("interrupt_mode")
          out.writeVal(mode)
        }
        out.writeKey("env")
        mapCodec.encodeValue(x.env, out)
        x.metadata.foreach { meta =>
          out.writeKey("metadata")
          out.writeRawVal(meta.value)
        }
        out.writeObjectEnd()
      }
    }
  }
}
