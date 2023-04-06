package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

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
    case object Range  extends AccessType("range")
    case object Tail   extends AccessType("tail")
    case object Search extends AccessType("search")

    val seq = Seq[AccessType](Range, Tail, Search)
    val map = seq.map(t => t.name -> t).toMap
  }

  def requestType = MessageType[Request]("history_request")
  def replyType   = MessageType[Reply]("history_reply")

  implicit val accessTypeCodec: JsonValueCodec[AccessType] = new JsonValueCodec[AccessType] {
    val stringCodec = JsonCodecMaker.make[String]
    def decodeValue(in: JsonReader, default: AccessType): AccessType = {
      val name = stringCodec.decodeValue(in, default.name)
      AccessType.map.getOrElse(
        name,
        throw new Exception(s"Unrecognized access type '$name'")
      )
    }
    def encodeValue(x: AccessType, out: JsonWriter): Unit =
      stringCodec.encodeValue(x.name, out)
    val nullValue: AccessType =
      AccessType.Range // ???
  }

  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make

  implicit val replyCodec: JsonValueCodec[Reply] = {

    implicit val simpleReplyCodec: JsonValueCodec[Reply.Simple] =
      JsonCodecMaker.make[Reply.Simple]
    implicit val withOutputReplyCodec: JsonValueCodec[Reply.WithOutput] =
      JsonCodecMaker.make[Reply.WithOutput]

    new JsonValueCodec[Reply] {
      def decodeValue(in: JsonReader, default: Reply): Reply = ???
      def encodeValue(reply: Reply, out: JsonWriter): Unit =
        reply match {
          case s: Reply.Simple =>
            simpleReplyCodec.encodeValue(s, out)
          case w: Reply.WithOutput =>
            withOutputReplyCodec.encodeValue(w, out)
        }
      def nullValue: Reply =
        simpleReplyCodec.nullValue
    }
  }

}
