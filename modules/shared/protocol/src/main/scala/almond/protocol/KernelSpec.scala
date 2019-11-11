package almond.protocol

// See http://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs

final case class KernelSpec(
  argv: List[String],
  display_name: String,
  language: String,
  interrupt_mode: Option[String] = None,
  env: Map[String, String] = Map.empty,
  metadata: Option[RawJson] = None
)
