package almond.protocol

final case class Status private[protocol] (
  execution_state: String
)

object Status {

  def starting = Status("starting")
  def busy = Status("busy")
  def idle = Status("idle")


  def messageType = MessageType[Status]("status")

}
