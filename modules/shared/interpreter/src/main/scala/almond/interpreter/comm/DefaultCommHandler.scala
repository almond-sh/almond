package almond.interpreter.comm

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.api.{CommHandler, CommTarget, DisplayData}
import almond.interpreter.util.DisplayDataOps._
import almond.interpreter.Message
import almond.protocol._
import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext

final class DefaultCommHandler(
  queue: Queue[IO, (Channel, RawMessage)],
  commEc: ExecutionContext
) extends CommHandler {

  import com.github.plokhotnyuk.jsoniter_scala.core._

  val commTargetManager = CommTargetManager.create()

  private val message: Message[_] =
    Message(
      header =
        Header(
          "",
          "username",
          "",
          "",
          Some(Protocol.versionStr)
        ), // FIXME Hardcoded user / session id
      content = ()
    )

  def registerCommTarget(name: String, target: CommTarget): Unit =
    registerCommTarget(name, IOCommTarget.fromCommTarget(target, commEc))
  def unregisterCommTarget(name: String): Unit =
    commTargetManager.removeTarget(name)

  def registerCommTarget(name: String, target: IOCommTarget): Unit =
    commTargetManager.addTarget(name, target)

  def registerCommId(id: String, target: CommTarget): Unit =
    commTargetManager.addId(IOCommTarget.fromCommTarget(target, commEc), id)
  def unregisterCommId(id: String): Unit =
    commTargetManager.removeId(id)

  private def publish[T: JsonValueCodec](
    messageType: MessageType[T],
    content: T,
    metadata: Array[Byte]
  ): Unit =
    message
      .publish(messageType, content, RawJson(metadata))
      .enqueueOn(Channel.Publish, queue)
      .unsafeRunSync()(IORuntime.global)

  def commOpen(targetName: String, id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    publish(Comm.openType, Comm.Open(id, targetName, RawJson(data)), metadata)

  def commMessage(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    publish(Comm.messageType, Comm.Message(id, RawJson(data)), metadata)

  def commClose(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    publish(Comm.closeType, Comm.Close(id, RawJson(data)), metadata)

  def updateDisplay(data: DisplayData): Unit = {

    assert(data.idOpt.nonEmpty, "Cannot update display data that has no id")

    val content = Execute.DisplayData(
      data.jsonData,
      data.jsonMetadata,
      Execute.DisplayData.Transient(data.idOpt)
    )

    publish(Execute.updateDisplayDataType, content, RawJson.emptyObj.value)
  }

  def canOutput(): Boolean = true
}
