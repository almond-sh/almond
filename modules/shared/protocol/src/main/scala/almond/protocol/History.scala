package almond.protocol

import argonaut.ArgonautShapeless._
import argonaut.{DecodeJson, DecodeResult, EncodeJson}

object History {

  // TODO Handle these

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


  sealed abstract class AccessType extends Product with Serializable

  object AccessType {
    case object Range extends AccessType
    case object Tail extends AccessType
    case object Search extends AccessType

    implicit val decoder: DecodeJson[AccessType] =
      DecodeJson { c =>
        c.as[String].flatMap {
          case "range" => DecodeResult.ok(Range)
          case "tail" => DecodeResult.ok(Tail)
          case "search" => DecodeResult.ok(Search)
          case other => DecodeResult.fail(s"Unrecognized history access type $other", c.history)
        }
      }
  }


  def requestType = MessageType[Request]("history_request")
  def replyType = MessageType[Reply]("history_reply")


  implicit val requestDecoder = DecodeJson.of[Request]

  private val simpleReplyEncoder = EncodeJson.of[Reply.Simple]
  private val withOutputReplyEncoder = EncodeJson.of[Reply.WithOutput]

  implicit val replyEncoder: EncodeJson[Reply] =
    EncodeJson {
      case simple: Reply.Simple =>
        simpleReplyEncoder(simple)
      case withOutput: Reply.WithOutput =>
        withOutputReplyEncoder(withOutput)
    }

}
