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

  // Where messages are sent while a cell is running: to the queue of that cell, along with its
  // outputs. Messages sent to our own queue are merged with the outputs of the cells, without any
  // guarantee about their relative order. Sending them along with the outputs ensures, for
  // example, that the comm_open message of a widget reaches the front-end before the
  // display_data message that shows it. Like the outputs of the cell, these messages have the
  // execute request as parent (some clients, like nbclient, rely on that to process them).
  private var executeOpt =
    Option.empty[(Queue[IO, Either[Throwable, (Channel, RawMessage)]], Header)]
  private val executeLock = new Object

  /** Runs `io`, sending the messages published in the meantime to `executeQueue`
    *
    * `executeQueue` is the queue of the messages of a cell, that shouldn't be closed while `io` is
    * running.
    *
    * @param parent
    *   header of the execute request of the cell, used as the parent of the messages
    */
  private[almond] def sendingTo[T](
    executeQueue: Queue[IO, Either[Throwable, (Channel, RawMessage)]],
    parent: Header
  )(io: IO[T]): IO[T] = {
    def set(value: Option[(Queue[IO, Either[Throwable, (Channel, RawMessage)]], Header)]) =
      IO {
        executeLock.synchronized {
          executeOpt = value
        }
      }
    set(Some((executeQueue, parent))).bracket(_ => io)(_ => set(None))
  }

  private def publish[T: JsonValueCodec](
    messageType: MessageType[T],
    content: T,
    metadata: Array[Byte],
    buffers: Seq[Array[Byte]] = Nil
  ): Unit = {
    val msg = Message
      .publish(session, messageType, content, RawJson(metadata))
      .copy(buffers = buffers)
    // holding the lock while enqueuing, so that the execute queue isn't closed in the meantime
    executeLock.synchronized {
      // messages sent from comm targets have the message they process as parent
      val callbackParent = IOCommTarget.currentParent.get()
      val enqueue = executeOpt match {
        case Some((executeQueue, executeParent)) =>
          msg
            .copy(parent_header = callbackParent.orElse(Some(executeParent)))
            .enqueueOn0(Channel.Publish, executeQueue)
        case None =>
          msg
            .copy(parent_header = callbackParent)
            .enqueueOn(Channel.Publish, queue)
      }
      enqueue.unsafeRunSync()(ioRuntime)
    }
  }

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
