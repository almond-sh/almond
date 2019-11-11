package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

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

    // implicit val decoder: DecodeJson[AccessType] =
    //   DecodeJson { c =>
    //     c.as[String].flatMap { s =>
    //       map.get(s) match {
    //         case Some(t) => DecodeResult.ok(t)
    //         case None => DecodeResult.fail(s"Unrecognized history access type $s", c.history)
    //       }
    //     }
    //   }
    // implicit val encoder: EncodeJson[AccessType] =
    //   EncodeJson(t => Json.jString(t.name))
  }


  def requestType = MessageType[Request]("history_request")
  def replyType = MessageType[Reply]("history_reply")


  // private val simpleReplyEncoder = EncodeJson.of[Reply.Simple]
  // private val withOutputReplyEncoder = EncodeJson.of[Reply.WithOutput]

  // implicit val replyEncoder: EncodeJson[Reply] =
  //   EncodeJson {
  //     case simple: Reply.Simple =>
  //       simpleReplyEncoder(simple)
  //     case withOutput: Reply.WithOutput =>
  //       withOutputReplyEncoder(withOutput)
  //   }


  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make(CodecMakerConfig)

  implicit val replyCodec: JsonValueCodec[Reply] = {

    implicit val simpleReplyCodec: JsonValueCodec[Reply.Simple] =
      JsonCodecMaker.make(CodecMakerConfig)
    implicit val withOutputReplyCodec: JsonValueCodec[Reply.WithOutput] =
      JsonCodecMaker.make(CodecMakerConfig)

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
