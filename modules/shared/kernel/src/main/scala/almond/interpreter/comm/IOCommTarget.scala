package almond.interpreter.comm

import almond.interpreter.api.CommTarget
import almond.protocol.Header
import cats.effect.IO

import scala.concurrent.ExecutionContext

trait IOCommTarget {

  /** @param parent
    *   header of the comm_open message, if any. Comm messages sent in response have it as parent.
    */
  def open(
    id: String,
    data: Array[Byte],
    buffers: Seq[Array[Byte]],
    parent: Option[Header]
  ): IO[Unit]

  /** @param parent
    *   header of the comm_msg message, if any. Comm messages sent in response have it as parent.
    */
  def message(
    id: String,
    data: Array[Byte],
    buffers: Seq[Array[Byte]],
    parent: Option[Header]
  ): IO[Unit]

  def close(id: String, data: Array[Byte]): IO[Unit]
}

object IOCommTarget {

  def fromCommTarget(commTarget: CommTarget, commEc: ExecutionContext): IOCommTarget =
    new FromCommTarget(commTarget, commEc)

  /** Header of the message being processed by the current thread, if any
    *
    * Set while the methods of [[CommTarget]]s run. Messages sent in the meantime have it as parent
    * (see [[DefaultCommHandler]]).
    */
  private[comm] val currentParent = new ThreadLocal[Option[Header]] {
    override def initialValue(): Option[Header] = None
  }

  private def withParent[T](parent: Option[Header])(f: => T): T = {
    val previous = currentParent.get()
    currentParent.set(parent)
    try f
    finally currentParent.set(previous)
  }

  final class FromCommTarget(commTarget: CommTarget, commEc: ExecutionContext)
      extends IOCommTarget {

    def open(
      id: String,
      data: Array[Byte],
      buffers: Seq[Array[Byte]],
      parent: Option[Header]
    ): IO[Unit] =
      IO(withParent(parent)(commTarget.open(id, data, buffers))).evalOn(commEc)
    def message(
      id: String,
      data: Array[Byte],
      buffers: Seq[Array[Byte]],
      parent: Option[Header]
    ): IO[Unit] =
      IO(withParent(parent)(commTarget.message(id, data, buffers))).evalOn(commEc)
    def close(id: String, data: Array[Byte]): IO[Unit] =
      IO(commTarget.close(id, data)).evalOn(commEc)
  }

}
