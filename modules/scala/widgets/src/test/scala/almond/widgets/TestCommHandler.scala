package almond.widgets

import almond.interpreter.api.{CommHandler, CommTarget, DisplayData}

import java.nio.charset.StandardCharsets

import scala.collection.mutable

/** Records what's sent to the front-end, and lets tests send messages as a front-end would */
final class TestCommHandler extends CommHandler {

  import TestCommHandler._

  val targets = new mutable.HashMap[String, CommTarget]
  val ids     = new mutable.HashMap[String, CommTarget]
  val sent    = new mutable.ListBuffer[Sent]

  def registerCommTarget(name: String, target: CommTarget): Unit =
    synchronized(targets(name) = target)
  def unregisterCommTarget(name: String): Unit =
    synchronized(targets -= name)
  def registerCommId(id: String, target: CommTarget): Unit =
    synchronized(ids(id) = target)
  def unregisterCommId(id: String): Unit =
    synchronized(ids -= id)

  def commOpen(targetName: String, id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    commOpen(targetName, id, data, metadata, Nil)
  def commMessage(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    commMessage(id, data, metadata, Nil)
  def commClose(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    synchronized(sent += Sent("close", id, ujson.read(data), ujson.read(metadata), Nil))

  override def commOpen(
    targetName: String,
    id: String,
    data: Array[Byte],
    metadata: Array[Byte],
    buffers: Seq[Array[Byte]]
  ): Unit = {
    assert(targetName == WidgetManager.targetName)
    synchronized(sent += Sent(
      "open",
      id,
      ujson.read(data),
      ujson.read(metadata),
      buffers
    ))
  }
  override def commMessage(
    id: String,
    data: Array[Byte],
    metadata: Array[Byte],
    buffers: Seq[Array[Byte]]
  ): Unit =
    synchronized(sent += Sent("msg", id, ujson.read(data), ujson.read(metadata), buffers))

  def updateDisplay(displayData: DisplayData): Unit =
    throw new UnsupportedOperationException

  /** Returns the messages sent so far, and forgets about them */
  def take(): List[Sent] =
    synchronized {
      val l = sent.toList
      sent.clear()
      l
    }

  // front-end side

  def frontendMessage(id: String, data: ujson.Value, buffers: Seq[Array[Byte]] = Nil): Unit = {
    val target = synchronized(ids.get(id)).getOrElse(sys.error(s"Unknown comm $id"))
    target.message(id, data.render().getBytes(StandardCharsets.UTF_8), buffers)
  }

  def frontendOpen(
    targetName: String,
    id: String,
    data: ujson.Value,
    buffers: Seq[Array[Byte]] = Nil
  ): Unit = {
    val target = synchronized(targets.get(targetName)).getOrElse(
      sys.error(s"Unknown target $targetName")
    )
    // like the kernel does, before passing comm_open messages to targets
    registerCommId(id, target)
    target.open(id, data.render().getBytes(StandardCharsets.UTF_8), buffers)
  }
}

object TestCommHandler {
  final case class Sent(
    kind: String,
    id: String,
    data: ujson.Value,
    metadata: ujson.Value,
    buffers: Seq[Array[Byte]]
  ) {
    def method: Option[String] =
      data.objOpt.flatMap(_.get("method")).flatMap(_.strOpt)
    def state: ujson.Obj =
      data.objOpt.flatMap(_.get("state")) match {
        case Some(s: ujson.Obj) => s
        case _                  => ujson.Obj()
      }
    def bufferPaths: ujson.Value =
      data.objOpt.flatMap(_.get("buffer_paths")).getOrElse(ujson.Null)
  }
}
