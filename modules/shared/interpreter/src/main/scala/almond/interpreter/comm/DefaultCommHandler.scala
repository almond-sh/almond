package almond.interpreter.comm

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.api.{CommHandler, CommTarget, DisplayData}
import almond.interpreter.util.DisplayDataOps._
import almond.interpreter.Message
import almond.protocol._
import argonaut.{EncodeJson, JsonObject}
import argonaut.Parse.{parse => parseJson}
import cats.effect.IO
import fs2.async.mutable.Queue

import scala.concurrent.ExecutionContext

final class DefaultCommHandler(
  commManager: CommManager,
  queue: Queue[IO, (Channel, RawMessage)],
  commEc: ExecutionContext
) extends CommHandler {

  private val message: Message[_] =
    Message(
      Header("", "username", "", "", Some(Protocol.versionStr)), // FIXME Hardcoded user / session id
      ()
    )


  def registerCommTarget(name: String, target: CommTarget): Unit =
    commManager.addTarget(name, IOCommTarget.fromCommTarget(target, commEc))
  def unregisterCommTarget(name: String): Unit =
    commManager.removeTarget(name)


  private def publish[T: EncodeJson](messageType: MessageType[T], content: T): Unit =
    message
      .publish(messageType, content)
      .enqueueOn(Channel.Publish, queue)
      .unsafeRunSync()

  private def parseJsonObj(s: String): Option[JsonObject] =
    parseJson(s)
      .right
      .toOption
      .flatMap(_.obj)

  // TODO Throw an exception if bad data is passed

  def commOpen(targetName: String, id: String, data: String): Unit =
    for (obj <- parseJsonObj(data))
      publish(Comm.openType, Comm.Open(id, targetName, obj))

  def commMessage(id: String, data: String): Unit =
    for (obj <- parseJsonObj(data))
      publish(Comm.messageType, Comm.Message(id, obj))

  def commClose(id: String, data: String): Unit =
    for (obj <- parseJsonObj(data))
      publish(Comm.closeType, Comm.Close(id, obj))


  def updateDisplay(data: DisplayData): Unit = {

    assert(data.idOpt.nonEmpty, "Cannot update display data that has no id")

    val content = Execute.DisplayData(
      data.jsonData,
      data.jsonMetadata,
      Execute.DisplayData.Transient(data.idOpt)
    )

    publish(Execute.updateDisplayDataType, content)
  }
}
