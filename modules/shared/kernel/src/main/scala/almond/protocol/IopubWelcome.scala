package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** Sent on IOPub when a client subscribes to it (protocol 5.5)
  *
  * @param subscription
  *   the topic the client subscribed to (empty if it subscribed to all topics)
  */
final case class IopubWelcome(
  subscription: String
)

object IopubWelcome {

  def messageType = MessageType[IopubWelcome]("iopub_welcome")

  implicit val codec: JsonValueCodec[IopubWelcome] =
    JsonCodecMaker.make

}
