package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

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
    final case class Success private[protocol] (
      execution_count: Int,
      user_expressions: Map[String, RawJson],
      status: String, // no default value here for the value not to be swallowed by the JSON encoder
      payload: List[RawJson]
    ) extends Reply {
      assert(status == "ok")
    }

    object Success {

      final case class AskExitPayload(
        source: String,
        keepkernel: Boolean
      )

      def apply(
        execution_count: Int,
        user_expressions: Map[String, RawJson], // value type?
        payload: List[RawJson] = List[RawJson]()
      ): Success =
        Success(
          execution_count,
          user_expressions,
          "ok",
          payload
        )
    }

    final case class Error private[protocol] (
      ename: String,
      evalue: String,
      traceback: List[String],
      status: String, // no default value here for the value not to be swallowed by the JSON encoder
      execution_count: Int =
        -1 // required in some context (e.g. errored execute_reply from jupyter console)
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

    final case class Abort private[protocol] (
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
    data: Map[String, RawJson], // same as DisplayData
    metadata: Map[String, RawJson],
    transient: DisplayData.Transient = DisplayData.Transient()
  )

  final case class Stream(
    name: String,
    text: String
  )

  final case class DisplayData(
    data: Map[
      String,
      RawJson
    ], // values are always strings, except if key corresponds to a JSON MIME type
    metadata: Map[String, RawJson],
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
  def inputType   = MessageType[Input]("execute_input")
  def resultType  = MessageType[Result]("execute_result")
  def replyType   = MessageType[Reply]("execute_reply")

  def errorType             = MessageType[Error]("error")
  def displayDataType       = MessageType[DisplayData]("display_data")
  def streamType            = MessageType[Stream]("stream")
  def updateDisplayDataType = MessageType[DisplayData]("update_display_data")

  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make

  implicit val replyCodec: JsonValueCodec[Reply] = {

    final case class Probe(status: String)

    implicit val probeCodec: JsonValueCodec[Probe] =
      JsonCodecMaker.make

    implicit val successCodec: JsonValueCodec[Reply.Success] =
      JsonCodecMaker.make[Reply.Success]
    implicit val errorCodec: JsonValueCodec[Reply.Error] =
      JsonCodecMaker.make[Reply.Error]
    implicit val abortCodec: JsonValueCodec[Reply.Abort] =
      JsonCodecMaker.make[Reply.Abort]

    new JsonValueCodec[Reply] {
      def decodeValue(in: JsonReader, default: Reply): Reply = {
        in.setMark()
        val probe = probeCodec.decodeValue(in, probeCodec.nullValue)
        in.rollbackToMark()
        probe.status match {
          case "ok" =>
            successCodec.decodeValue(in, successCodec.nullValue)
          case "error" =>
            errorCodec.decodeValue(in, errorCodec.nullValue)
          case "abort" =>
            abortCodec.decodeValue(in, abortCodec.nullValue)
          case _ =>
            ???
        }
      }
      def encodeValue(reply: Reply, out: JsonWriter): Unit =
        reply match {
          case s: Reply.Success => successCodec.encodeValue(s, out)
          case e: Reply.Error   => errorCodec.encodeValue(e, out)
          case a: Reply.Abort   => abortCodec.encodeValue(a, out)
        }
      def nullValue: Reply =
        Reply.Success(0, Map.empty, "ok", Nil)
    }
  }

  implicit val inputCodec: JsonValueCodec[Input] =
    JsonCodecMaker.make

  implicit val resultCodec: JsonValueCodec[Result] =
    JsonCodecMaker.makeWithRequiredCollectionFields[Result]

  implicit val streamCodec: JsonValueCodec[Stream] =
    JsonCodecMaker.make

  implicit val displayDataCodec: JsonValueCodec[DisplayData] =
    JsonCodecMaker.makeWithRequiredCollectionFields[DisplayData]

  implicit val errorCodec: JsonValueCodec[Error] =
    JsonCodecMaker.make

  implicit val askExitPayloadCodec: JsonValueCodec[Reply.Success.AskExitPayload] =
    JsonCodecMaker.make[Reply.Success.AskExitPayload]

}
