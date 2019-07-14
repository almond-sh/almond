package almond.interpreter.comm

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.api.{CommHandler, CommTarget, DisplayData}
import almond.interpreter.util.DisplayDataOps._
import almond.interpreter.Message
import almond.protocol._
import argonaut.{EncodeJson, Json, JsonObject}
import argonaut.Parse.{parse => parseJson}
import cats.effect.IO
import fs2.concurrent.Queue

import scala.concurrent.ExecutionContext
import scala.util.Try

final class DefaultCommHandler(
  queue: Queue[IO, (Channel, RawMessage)],
  commEc: ExecutionContext
) extends CommHandler {

  val commTargetManager = CommTargetManager.create()

  private val message: Message[_] =
    Message(
      header = Header("", "username", "", "", Some(Protocol.versionStr)), // FIXME Hardcoded user / session id
      content = ()
    )


  def registerCommTarget(name: String, target: CommTarget): Unit =
    registerCommTarget(name, IOCommTarget.fromCommTarget(target, commEc))
  def unregisterCommTarget(name: String): Unit =
    commTargetManager.removeTarget(name)

  def registerCommTarget(name: String, target: IOCommTarget): Unit =
    commTargetManager.addTarget(name, target)


  private def publish[T: EncodeJson](messageType: MessageType[T], content: T, metadata: Map[String, Json]): Unit =
    message
      .publish(messageType, content, metadata)
      .enqueueOn(Channel.Publish, queue)
      .unsafeRunSync()

  private def parseJsonObj(s: String): Try[JsonObject] =
    parseJson(s)
      .right.flatMap(_.obj.toRight("Not a JSON object"))
      .left.map(new IllegalArgumentException(_))
      .toTry

  def commOpen(targetName: String, id: String, data: String, metadata: String): Unit =
    publish(Comm.openType, Comm.Open(id, targetName, parseJsonObj(data).get), parseJsonObj(metadata).get.toMap)

  def commMessage(id: String, data: String, metadata: String): Unit =
    publish(Comm.messageType, Comm.Message(id, parseJsonObj(data).get), parseJsonObj(metadata).get.toMap)

  def commClose(id: String, data: String, metadata: String): Unit =
    publish(Comm.closeType, Comm.Close(id, parseJsonObj(data).get), parseJsonObj(metadata).get.toMap)


  def updateDisplay(data: DisplayData): Unit = {

    assert(data.idOpt.nonEmpty, "Cannot update display data that has no id")

    val content = Execute.DisplayData(
      data.jsonData,
      data.jsonMetadata,
      Execute.DisplayData.Transient(data.idOpt)
    )

    publish(Execute.updateDisplayDataType, content, Map.empty)
  }
}
