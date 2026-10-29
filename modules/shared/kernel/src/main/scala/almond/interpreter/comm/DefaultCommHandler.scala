package almond.interpreter.comm

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.api.{CommHandler, CommTarget, DisplayData}
import almond.interpreter.util.DisplayDataOps._
import almond.interpreter.{KernelSession, Message}
import almond.protocol._
import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime

import java.util.IdentityHashMap

import scala.concurrent.ExecutionContext

final class DefaultCommHandler(
  queue: Queue[IO, (Channel, RawMessage)],
  session: KernelSession,
  commEc: ExecutionContext,
  ioRuntime: IORuntime
) extends CommHandler {

  import com.github.plokhotnyuk.jsoniter_scala.core._

  val commTargetManager = CommTargetManager.create()

  // The IOCommTarget-s wrapping the targets registered by name, so that comm ids registered with
  // one of these targets end up associated to that target name (see CommTargetManager.allInfos,
  // used to answer comm_info requests)
  private val namedTargets = new IdentityHashMap[CommTarget, IOCommTarget]

  private def ioTarget(target: CommTarget): IOCommTarget =
    namedTargets.synchronized {
      Option(namedTargets.get(target))
        .getOrElse(IOCommTarget.fromCommTarget(target, commEc))
    }

  def registerCommTarget(name: String, target: CommTarget): Unit =
    namedTargets.synchronized {
      val ioTarget0 = ioTarget(target)
      registerCommTarget(name, ioTarget0)
      namedTargets.put(target, ioTarget0)
    }
  def unregisterCommTarget(name: String): Unit =
    namedTargets.synchronized {
      for (ioTarget0 <- commTargetManager.target(name)) {
        val it = namedTargets.entrySet().iterator()
        while (it.hasNext)
          if (it.next().getValue eq ioTarget0)
            it.remove()
      }
      commTargetManager.removeTarget(name)
    }

  def registerCommTarget(name: String, target: IOCommTarget): Unit =
    commTargetManager.addTarget(name, target)

  def registerCommId(id: String, target: CommTarget): Unit =
    commTargetManager.addId(ioTarget(target), id)
  def unregisterCommId(id: String): Unit =
    commTargetManager.removeId(id)

  private def publish[T: JsonValueCodec](
    messageType: MessageType[T],
    content: T,
    metadata: Array[Byte],
    buffers: Seq[Array[Byte]] = Nil
  ): Unit =
    Message
      .publish(session, messageType, content, RawJson(metadata))
      .copy(
        buffers = buffers,
        // messages sent from comm targets have the message they process as parent
        parent_header = IOCommTarget.currentParent.get()
      )
      .enqueueOn(Channel.Publish, queue)
      .unsafeRunSync()(ioRuntime)

  def commOpen(targetName: String, id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    commOpen(targetName, id, data, metadata, Nil)

  def commMessage(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    commMessage(id, data, metadata, Nil)

  override def commOpen(
    targetName: String,
    id: String,
    data: Array[Byte],
    metadata: Array[Byte],
    buffers: Seq[Array[Byte]]
  ): Unit =
    publish(Comm.openType, Comm.Open(id, targetName, RawJson(data)), metadata, buffers)

  override def commMessage(
    id: String,
    data: Array[Byte],
    metadata: Array[Byte],
    buffers: Seq[Array[Byte]]
  ): Unit =
    publish(Comm.messageType, Comm.Message(id, RawJson(data)), metadata, buffers)

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
