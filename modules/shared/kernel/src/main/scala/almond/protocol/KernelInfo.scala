package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

final case class KernelInfo(
  status: String,           // "ok"
  protocol_version: String, // X.Y.Z
  implementation: String,
  implementation_version: String, // X.Y.Z
  language_info: KernelInfo.LanguageInfo,
  banner: String,
  help_links: Option[List[KernelInfo.Link]] = None,
  supported_features: List[String] = Nil, // since protocol 5.5
  lsp: Option[KernelInfo.Lsp] = None      // not in the protocol, see Lsp
)

object KernelInfo {

  def apply(
    implementation: String,
    implementation_version: String,
    language_info: KernelInfo.LanguageInfo,
    banner: String,
    help_links: Option[List[KernelInfo.Link]]
  ): KernelInfo =
    KernelInfo(
      status = "ok",
      protocol_version = Protocol.versionStr,
      implementation = implementation,
      implementation_version = implementation_version,
      language_info = language_info,
      banner = banner,
      help_links = help_links
    )

  def apply(
    implementation: String,
    implementation_version: String,
    language_info: KernelInfo.LanguageInfo,
    banner: String
  ): KernelInfo =
    KernelInfo(
      status = "ok",
      protocol_version = Protocol.versionStr,
      implementation = implementation,
      implementation_version = implementation_version,
      language_info = language_info,
      banner = banner,
      help_links = None
    )

  final case class LanguageInfo(
    name: String,
    version: String, // X.Y.Z
    mimetype: String,
    file_extension: String, // including the dot
    nbconvert_exporter: String,
    pygments_lexer: Option[String] = None, // only needed if it differs from name
    codemirror_mode: Option[String] =
      None // only needed if it differs from name - FIXME could be a dict too?
  )

  final case class Link(text: String, url: String)

  /** How the frontend should send notebooks to the language server of the kernel
    *
    * Not part of the Jupyter protocol: read by JupyterLab, for kernels listing `lsp` in
    * `supported_features`.
    *
    * @param cell_separator_prefix
    *   prefix of the lines that the frontend should put before each cell, in the single document it
    *   makes of the cells of a notebook, followed by a space and a JSON object with details about
    *   the cell
    * @param document_roots
    *   directories whose files (other than notebooks) the language server of the kernel handles
    *   too, like the sources of dependencies that definitions lead to
    */
  final case class Lsp(
    cell_separator_prefix: Option[String] = None,
    document_roots: List[String] = Nil
  )

  def requestType = MessageType[Unit]("kernel_info_request")
  def replyType   = MessageType[KernelInfo]("kernel_info_reply")

  implicit val linkCodec: JsonValueCodec[Link] =
    JsonCodecMaker.make
  implicit val codec: JsonValueCodec[KernelInfo] =
    // supported_features is required since protocol 5.5, even when empty
    JsonCodecMaker.make(
      CodecMakerConfig
        .withTransientEmpty(false)
        .withTransientDefault(false)
    )

}
