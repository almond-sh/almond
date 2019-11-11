package almond.protocol

import almond.channels.ConnectionParameters
import almond.util.Secret

final case class Connection(
  ip: String,
  transport: String,
  stdin_port: Int,
  control_port: Int,
  hb_port: Int,
  shell_port: Int,
  iopub_port: Int,
  key: Secret[String],
  signature_scheme: Option[String],
  kernel_name: Option[String] = None // jupyter seems to add this
) {
  def connectionParameters =
    ConnectionParameters(
      ip,
      transport,
      stdin_port,
      control_port,
      hb_port,
      shell_port,
      iopub_port,
      key,
      signature_scheme,
      kernel_name
    )
}
