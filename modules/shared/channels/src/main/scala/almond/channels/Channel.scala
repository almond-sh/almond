package almond.channels

sealed abstract class Channel(val name: String) extends Product with Serializable

object Channel {
  case object Requests extends Channel("shell")
  case object Control  extends Channel("control")
  case object Publish  extends Channel("iopub")
  case object Input    extends Channel("stdin")

  val channels = Seq(Publish, Requests, Control, Input)

  val map = channels.map(c => c.name -> c).toMap
}
