package almond.protocol

import argonaut.{DecodeJson, DecodeResult, EncodeJson, Json}
import argonaut.Argonaut._
import argonaut.ArgonautShapeless._

object Execute {

  final case class Request(
    code: String,
    user_expressions: Map[String, String] = Map.empty,
    silent: Option[Boolean] = None,
    store_history: Option[Boolean] = None,
    allow_stdin: Option[Boolean] = None,
    stop_on_error: Option[Boolean] = None
  )

  sealed abstract class Reply extends Product with Serializable

  object Reply {

    // payloads not supported here
    final case class Success private (
      execution_count: Int,
      user_expressions: Map[String, Json],
      status: String // no default value here for the value not to be swallowed by the JSON encoder
    ) extends Reply {
      assert(status == "ok")
    }

    object Success {
      def apply(
        execution_count: Int,
        user_expressions: Map[String, Json] // value type?
      ): Success =
        Success(
          execution_count,
          user_expressions,
          "ok"
        )
    }


    final case class Error private (
      ename: String,
      evalue: String,
      traceback: List[String],
      status: String, // no default value here for the value not to be swallowed by the JSON encoder
      execution_count: Int = -1 // required in some context (e.g. errored execute_reply from jupyter console)
    ) extends Reply {
      assert(status == "error")
    }

    object Error {
      def apply(
        ename: String,
        evalue: String,
        traceback: List[String]
      ): Error =
        Error(
          ename,
          evalue,
          traceback,
          "error"
        )

      def apply(
        ename: String,
        evalue: String,
        traceback: List[String],
        execution_count: Int
      ): Error =
        Error(
          ename,
          evalue,
          traceback,
          "error",
          execution_count
        )
    }

    final case class Abort private (
      status: String // no default value here for the value not to be swallowed by the JSON encoder
    ) extends Reply {
      assert(status == "abort")
    }

    object Abort {
      def apply(): Abort =
        Abort("abort")
    }

  }


  final case class Input(
    code: String,
    execution_count: Int
  )

  final case class Result(
    execution_count: Int,
    data: Map[String, Json], // same as DisplayData
    metadata: Map[String, Json],
    transient: DisplayData.Transient = DisplayData.Transient()
  )

  final case class Stream(
    name: String,
    text: String
  )

  final case class DisplayData(
    data: Map[String, Json], // values are always strings, except if key corresponds to a JSON MIME type
    metadata: Map[String, Json],
    transient: DisplayData.Transient = DisplayData.Transient()
  )

  object DisplayData {
    final case class Transient(
      display_id: Option[String] = None
    )
  }

  final case class Error(
    ename: String,
    evalue: String,
    traceback: List[String]
  )


  def requestType = MessageType[Request]("execute_request")
  def inputType = MessageType[Input]("execute_input")
  def resultType = MessageType[Result]("execute_result")
  def replyType = MessageType[Reply]("execute_reply")

  def errorType = MessageType[Error]("error")
  def displayDataType = MessageType[DisplayData]("display_data")
  def streamType = MessageType[Stream]("stream")
  def updateDisplayDataType = MessageType[DisplayData]("update_display_data")


  implicit val requestDecoder = DecodeJson.of[Request]
  implicit val requestEncoder = EncodeJson.of[Request]

  implicit val replyEncoder: EncodeJson[Reply] =
    EncodeJson {
      case s: Reply.Success => s.asJson
      case err: Reply.Error => err.asJson
      case a: Reply.Abort => a.asJson
    }
  implicit val replyDecoder: DecodeJson[Reply] = {

    final case class Helper(status: String)
    implicit val helperDecoder = DecodeJson.of[Helper]

    DecodeJson { cursor =>
      helperDecoder(cursor).flatMap {
        case Helper("abort") => cursor.as[Reply.Abort].map(x => x) // map for variance stuff…
        case Helper("error") => cursor.as[Reply.Error].map(x => x)
        case Helper("ok") => cursor.as[Reply.Success].map(x => x)
        case Helper(other) => DecodeResult.fail(s"Unrecognized execute_reply status: $other", cursor.history)
      }
    }
  }

  implicit val inputEncoder = EncodeJson.of[Input]

  implicit val resultEncoder = EncodeJson.of[Result]

  implicit val streamEncoder = EncodeJson.of[Stream]

  implicit val displayDataDecoder = DecodeJson.of[DisplayData]
  implicit val displayDataEncoder = EncodeJson.of[DisplayData]

  implicit val errorEncoder = EncodeJson.of[Error]

}
