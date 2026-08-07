package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

// See http://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs

final case class KernelSpec(
  argv: List[String],
  display_name: String,
  language: String,
  interrupt_mode: Option[String] = None,
  // ActualMap is used instead of Map[String, String] to work around a
  // jsoniter-scala Scala 3 macro issue that incorrectly serializes
  // Map[String, String] as an array of pairs (see issue #1499).
  env: ActualMap[String] = ActualMap(Map.empty),
  metadata: Option[RawJson] = None
)

object KernelSpec {
  implicit val codec: JsonValueCodec[KernelSpec] = JsonCodecMaker.make
}
