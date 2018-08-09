package almond.protocol

import almond.protocol.internal.ExtraCodecs._
import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, EncodeJson, JsonObject}

// See http://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs

final case class KernelSpec(
  argv: List[String],
  display_name: String,
  language: String,
  interrupt_mode: Option[String] = None,
  env: Map[String, String] = Map.empty,
  metadata: Option[JsonObject] = None
)

object KernelSpec {

  implicit val decoder = DecodeJson.of[KernelSpec]
  implicit val encoder = EncodeJson.of[KernelSpec]

}