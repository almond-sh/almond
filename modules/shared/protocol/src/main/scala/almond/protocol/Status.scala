package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.EncodeJson

final case class Status private (
  execution_state: String
) extends AnyVal

object Status {

  def starting = Status("starting")
  def busy = Status("busy")
  def idle = Status("idle")


  def messageType = MessageType[Status]("status")


  implicit val encoder = EncodeJson.of[Status]

}
