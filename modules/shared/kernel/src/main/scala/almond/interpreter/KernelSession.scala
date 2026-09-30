package almond.interpreter

import java.util.UUID

import almond.protocol.{Header, MessageType, Protocol}

/** Identity of a kernel process, used in the header of all the messages it sends.
  *
  * The Jupyter protocol requires the session id of kernel messages to identify the kernel process,
  * and to stay the same during its lifetime. Frontends rely on it to detect kernel restarts.
  */
final case class KernelSession(
  id: String,
  username: String
) {

  /** Creates a header for a new message sent by this kernel
    */
  def header(messageType: MessageType[_]): Header =
    Header(
      msg_id = UUID.randomUUID().toString,
      username = username,
      session = id,
      msg_type = messageType.messageType,
      version = Some(Protocol.versionStr)
    )
}

object KernelSession {

  def defaultUsername: String =
    Option(System.getProperty("user.name")).filter(_.nonEmpty).getOrElse("username")

  def create(): KernelSession =
    create(None, None)

  /** @param idOpt
    *   session id to use - a random one is generated if empty
    * @param usernameOpt
    *   user name to use - [[defaultUsername]] is used if empty
    */
  def create(idOpt: Option[String], usernameOpt: Option[String]): KernelSession =
    KernelSession(
      idOpt.getOrElse(UUID.randomUUID().toString),
      usernameOpt.getOrElse(defaultUsername)
    )
}
