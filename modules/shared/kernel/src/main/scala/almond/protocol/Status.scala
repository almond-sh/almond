package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

final case class Status private[protocol] (
  execution_state: String
)

object Status {

  def starting = Status("starting")
  def busy     = Status("busy")
  def idle     = Status("idle")

  def messageType = MessageType[Status]("status")

  implicit val codec: JsonValueCodec[Status] =
    JsonCodecMaker.make

}
