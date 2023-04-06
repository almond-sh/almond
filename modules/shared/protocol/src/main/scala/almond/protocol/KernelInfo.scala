package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

final case class KernelInfo(
  status: String,           // "ok"
  protocol_version: String, // X.Y.Z
  implementation: String,
  implementation_version: String, // X.Y.Z
  language_info: KernelInfo.LanguageInfo,
  banner: String,
  help_links: Option[List[KernelInfo.Link]] = None
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

  def requestType = MessageType[Unit]("kernel_info_request")
  def replyType   = MessageType[KernelInfo]("kernel_info_reply")

  implicit val linkCodec: JsonValueCodec[Link] =
    JsonCodecMaker.make
  implicit val codec: JsonValueCodec[KernelInfo] =
    JsonCodecMaker.make

}
