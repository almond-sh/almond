package almond.protocol

import java.nio.file.{Files, Path}

import almond.util.Secret
import cats.effect.IO
import scala.util.Try

object Codecs {
  import com.github.plokhotnyuk.jsoniter_scala.core._
  import com.github.plokhotnyuk.jsoniter_scala.macros._


  implicit val commOpenCodec: JsonValueCodec[Comm.Open] =
    JsonCodecMaker.make[Comm.Open](CodecMakerConfig)
  implicit val commMessageCodec: JsonValueCodec[Comm.Message] =
    JsonCodecMaker.make[Comm.Message](CodecMakerConfig)
  implicit val commCloseCodec: JsonValueCodec[Comm.Close] =
    JsonCodecMaker.make[Comm.Close](CodecMakerConfig)

  implicit val commInfoRequestCodec: JsonValueCodec[CommInfo.Request] =
    JsonCodecMaker.make[CommInfo.Request](CodecMakerConfig)
  implicit val commInfoReplyCodec: JsonValueCodec[CommInfo.Reply] =
    JsonCodecMaker.make[CommInfo.Reply](CodecMakerConfig)

  implicit val completeRequestCodec: JsonValueCodec[Complete.Request] =
    JsonCodecMaker.make[Complete.Request](CodecMakerConfig)
  implicit val completeReplyCodec: JsonValueCodec[Complete.Reply] =
    JsonCodecMaker.make[Complete.Reply](CodecMakerConfig)

  implicit val connectRequestCodec: JsonValueCodec[Connect.Request.type] =
    JsonCodecMaker.make[Connect.Request.type](CodecMakerConfig)
  implicit val connectReplyCodec: JsonValueCodec[Connect.Reply] =
    JsonCodecMaker.make[Connect.Reply](CodecMakerConfig)

  implicit val connectionCodec: JsonValueCodec[Connection] = {

    final case class RawConnection(
      ip: String,
      transport: String,
      stdin_port: Int,
      control_port: Int,
      hb_port: Int,
      shell_port: Int,
      iopub_port: Int,
      key: String,
      signature_scheme: Option[String],
      kernel_name: Option[String] = None
    )

    implicit class RawConnectionOps(private val rawConn: RawConnection) {
      def connection: Connection =
        if (rawConn == null) null
        else
          Connection(
            rawConn.ip,
            rawConn.transport,
            rawConn.stdin_port,
            rawConn.control_port,
            rawConn.hb_port,
            rawConn.shell_port,
            rawConn.iopub_port,
            Secret(rawConn.key),
            rawConn.signature_scheme,
            rawConn.kernel_name
          )
    }

    def rawConnection(conn: Connection): RawConnection =
      if (conn == null) null
      else
        RawConnection(
          conn.ip,
          conn.transport,
          conn.stdin_port,
          conn.control_port,
          conn.hb_port,
          conn.shell_port,
          conn.iopub_port,
          conn.key.value,
          conn.signature_scheme,
          conn.kernel_name
        )


    val underlying: JsonValueCodec[RawConnection] =
      JsonCodecMaker.make[RawConnection](CodecMakerConfig)

    new JsonValueCodec[Connection] {
      def decodeValue(in: JsonReader, default: Connection): Connection =
        underlying.decodeValue(in, rawConnection(default)).connection
      def encodeValue(x: Connection, out: JsonWriter): Unit =
        underlying.encodeValue(rawConnection(x), out)
      def nullValue: Connection =
        underlying.nullValue.connection
    }
  }

  implicit val executeRequestCodec: JsonValueCodec[Execute.Request] =
    JsonCodecMaker.make[Execute.Request](CodecMakerConfig)

  implicit val executeReplyCodec: JsonValueCodec[Execute.Reply] = {

    final case class Probe(status: String)

    implicit val probeCodec: JsonValueCodec[Probe] =
      JsonCodecMaker.make[Probe](CodecMakerConfig)

    implicit val successCodec: JsonValueCodec[Execute.Reply.Success] =
      JsonCodecMaker.make[Execute.Reply.Success](CodecMakerConfig)
    implicit val errorCodec: JsonValueCodec[Execute.Reply.Error] =
      JsonCodecMaker.make[Execute.Reply.Error](CodecMakerConfig)
    implicit val abortCodec: JsonValueCodec[Execute.Reply.Abort] =
      JsonCodecMaker.make[Execute.Reply.Abort](CodecMakerConfig)

    new JsonValueCodec[Execute.Reply] {
      def decodeValue(in: JsonReader, default: Execute.Reply): Execute.Reply = {
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
      def encodeValue(reply: Execute.Reply, out: JsonWriter): Unit =
        reply match {
          case s: Execute.Reply.Success => successCodec.encodeValue(s, out)
          case e: Execute.Reply.Error => errorCodec.encodeValue(e, out)
          case a: Execute.Reply.Abort => abortCodec.encodeValue(a, out)
        }
      def nullValue: Execute.Reply =
        Execute.Reply.Success(0, Map.empty, "ok", Nil)
    }
  }

  implicit val executeInputCodec: JsonValueCodec[Execute.Input] =
    JsonCodecMaker.make[Execute.Input](CodecMakerConfig)

  implicit val executeResultCodec: JsonValueCodec[Execute.Result] =
    JsonCodecMaker.make[Execute.Result](CodecMakerConfig.withTransientEmpty(false))

  implicit val executeStreamCodec: JsonValueCodec[Execute.Stream] =
    JsonCodecMaker.make[Execute.Stream](CodecMakerConfig)

  implicit val executeDisplayDataCodec: JsonValueCodec[Execute.DisplayData] =
    JsonCodecMaker.make[Execute.DisplayData](CodecMakerConfig.withTransientEmpty(false))

  implicit val executeErrorCodec: JsonValueCodec[Execute.Error] =
    JsonCodecMaker.make[Execute.Error](CodecMakerConfig)

  implicit val executeAskExitPayloadCodec: JsonValueCodec[Execute.Reply.Success.AskExitPayload] =
    JsonCodecMaker.make[Execute.Reply.Success.AskExitPayload](CodecMakerConfig)

  implicit val headerCodec: JsonValueCodec[Header] =
    JsonCodecMaker.make[Header](CodecMakerConfig)

  implicit val historyRequestCodec: JsonValueCodec[History.Request] =
    JsonCodecMaker.make[History.Request](CodecMakerConfig)

  implicit val historyReplyCodec: JsonValueCodec[History.Reply] = {

    implicit val simpleReplyCodec: JsonValueCodec[History.Reply.Simple] =
      JsonCodecMaker.make[History.Reply.Simple](CodecMakerConfig)
    implicit val withOutputReplyCodec: JsonValueCodec[History.Reply.WithOutput] =
      JsonCodecMaker.make[History.Reply.WithOutput](CodecMakerConfig)

    new JsonValueCodec[History.Reply] {
      def decodeValue(in: JsonReader, default: History.Reply): History.Reply = ???
      def encodeValue(reply: History.Reply, out: JsonWriter): Unit =
        reply match {
          case s: History.Reply.Simple =>
            simpleReplyCodec.encodeValue(s, out)
          case w: History.Reply.WithOutput =>
            withOutputReplyCodec.encodeValue(w, out)
        }
      def nullValue: History.Reply =
        simpleReplyCodec.nullValue
    }
  }

  implicit val inputRequestCodec: JsonValueCodec[Input.Request] =
    JsonCodecMaker.make[Input.Request](CodecMakerConfig)
  implicit val inputReplyCodec: JsonValueCodec[Input.Reply] =
    JsonCodecMaker.make[Input.Reply](CodecMakerConfig)

  implicit val inspectRequestCodec: JsonValueCodec[Inspect.Request] =
    JsonCodecMaker.make[Inspect.Request](CodecMakerConfig)
  implicit val inspectReplyCodec: JsonValueCodec[Inspect.Reply] =
    JsonCodecMaker.make[Inspect.Reply](CodecMakerConfig)

  implicit val interruptRequestCodec: JsonValueCodec[Interrupt.Request.type] =
    JsonCodecMaker.make[Interrupt.Request.type](CodecMakerConfig)
  implicit val interruptReplyCodec: JsonValueCodec[Interrupt.Reply.type] =
    JsonCodecMaker.make[Interrupt.Reply.type](CodecMakerConfig)

  implicit val isCompleteRequestCodec: JsonValueCodec[IsComplete.Request] =
    JsonCodecMaker.make[IsComplete.Request](CodecMakerConfig)
  implicit val isCompleteReplyCodec: JsonValueCodec[IsComplete.Reply] =
    JsonCodecMaker.make[IsComplete.Reply](CodecMakerConfig)

  implicit val kernelInfoLinkCodec: JsonValueCodec[KernelInfo.Link] =
    JsonCodecMaker.make[KernelInfo.Link](CodecMakerConfig)
  implicit val kernelInfoCodec: JsonValueCodec[KernelInfo] =
    JsonCodecMaker.make[KernelInfo](CodecMakerConfig)

  implicit val kernelSpecCodec: JsonValueCodec[KernelSpec] =
    JsonCodecMaker.make[KernelSpec](CodecMakerConfig)

  implicit val shutdownRequestCodec: JsonValueCodec[Shutdown.Request] =
    JsonCodecMaker.make[Shutdown.Request](CodecMakerConfig)
  implicit val shutdownReplyCodec: JsonValueCodec[Shutdown.Reply] =
    JsonCodecMaker.make[Shutdown.Reply](CodecMakerConfig)

  implicit val statusCodec: JsonValueCodec[Status] =
    JsonCodecMaker.make[Status](CodecMakerConfig)


  implicit class ConnectionCompanionOps(private val obj: Connection.type) extends AnyVal {

    def fromPath(path: Path): IO[Connection] =
      for {
        b <- IO(Files.readAllBytes(path))
        c <- {
          Try(readFromArray(b)(connectionCodec)).toEither match {
            case Left(e) =>
              IO.raiseError(new Exception(s"Error parsing $path", e))
            case Right(c) =>
              IO.pure(c)
          }
        }
      } yield c

  }

  implicit val unitCodec: JsonValueCodec[Unit] = {
    final case class Empty()
    val empty = Empty()
    val emptyCodec = JsonCodecMaker.make[Empty](CodecMakerConfig)

    new JsonValueCodec[Unit] {
      def decodeValue(in: JsonReader, default: Unit) = emptyCodec.decodeValue(in, empty)
      def encodeValue(x: Unit, out: JsonWriter) = emptyCodec.encodeValue(empty, out)
      def nullValue = ()
    }
  }

  implicit val stringCodec: JsonValueCodec[String] =
    JsonCodecMaker.make[String](CodecMakerConfig)


}
