package almond.protocol.custom

import java.nio.charset.StandardCharsets

import almond.protocol.{MessageType, RawJson}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import scala.collection.immutable.ListMap

object Format {

  final case class Request(
    cells: ListMap[String, String],
    conf: RawJson = RawJson.emptyObj
  )

  /** Formatting result for a single cell
    *
    * @param code
    *   formatted code, empty if the cell couldn't be formatted
    * @param error
    *   why the cell couldn't be formatted, if it couldn't
    */
  final case class Response(
    key: String,
    initial_code: String,
    code: Option[String] = None,
    error: Option[String] = None
  )

  final case class Reply()

  def requestType  = MessageType[Request]("format_request")
  def responseType = MessageType[Response]("format_response")
  def replyType    = MessageType[Reply]("format_reply")

  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make
  implicit val responseCodec: JsonValueCodec[Response] =
    JsonCodecMaker.make
  implicit val replyCodec: JsonValueCodec[Reply] =
    JsonCodecMaker.make

}
