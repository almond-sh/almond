package almond.protocol

object History {

  final case class Request(
    output: Boolean,
    raw: Boolean,
    hist_access_type: AccessType,
    session: Option[Int] = None,
    start: Option[Int] = None,
    stop: Option[Int] = None,
    n: Option[Int] = None,
    pattern: Option[String] = None,
    unique: Boolean = false
  )

  sealed abstract class Reply extends Product with Serializable

  object Reply {

    final case class Simple(history: List[(Int, Int, String)]) extends Reply

    final case class WithOutput(history: List[(Int, Int, (String, String))]) extends Reply

  }


  sealed abstract class AccessType(val name: String) extends Product with Serializable

  object AccessType {
    case object Range extends AccessType("range")
    case object Tail extends AccessType("tail")
    case object Search extends AccessType("search")

    val seq = Seq[AccessType](Range, Tail, Search)
    val map = seq.map(t => t.name -> t).toMap
  }


  def requestType = MessageType[Request]("history_request")
  def replyType = MessageType[Reply]("history_reply")

}
