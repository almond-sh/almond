package almond.launcher

import almond.interpreter.api.OutputHandler
import almond.interpreter.util.DisplayDataOps._

import java.util.concurrent.LinkedBlockingQueue
import almond.interpreter.Message
import almond.protocol.RawJson
import almond.protocol.Execute
import cats.effect.unsafe.IORuntime
import scala.util.control.NonFatal
import almond.channels.Connection
import almond.channels.Channel

object LauncherOutputHandler {

  private def stdoutPoisonPill: String = null

  private sealed abstract class Value             extends Product with Serializable
  private final case class Stdout(stdout: String) extends Value
  private final case class Stderr(stderr: String) extends Value
  private final case class Display(data: almond.interpreter.api.DisplayData) extends Value
  private final case class UpdateDisplay(updateData: almond.interpreter.api.DisplayData)
      extends Value

}

class LauncherOutputHandler(
  firstMessage: Message[RawJson],
  conn: Connection
) extends OutputHandler {
  import LauncherOutputHandler._
  def done(): Unit = {
    stdout(stdoutPoisonPill)
    thread.join()
  }
  private val poisonPill = Stdout(stdoutPoisonPill)
  private val queue      = new LinkedBlockingQueue[Value]
  private val thread: Thread =
    new Thread("publish-queue") {
      setDaemon(true)
      override def run(): Unit = {
        var value: Value = null
        while ({
          value = queue.take()
          value != poisonPill
        }) {
          val msg0 = value match {
            case Stderr(stdErrMsg) =>
              firstMessage.publish(
                Execute.streamType,
                Execute.Stream("stderr", stdErrMsg),
                ident = Some("stderr")
              ).asRawMessage
            case Stdout(stdOutMsg) =>
              firstMessage.publish(
                Execute.streamType,
                Execute.Stream("stdout", stdOutMsg),
                ident = Some("stdout")
              ).asRawMessage
            case Display(data) =>
              val content = Execute.DisplayData(
                data.jsonData,
                data.jsonMetadata,
                Execute.DisplayData.Transient(data.idOpt)
              )
              firstMessage.publish(
                Execute.displayDataType,
                content,
                ident = Some(Execute.displayDataType.messageType)
              ).asRawMessage
            case UpdateDisplay(data) =>
              val content = Execute.DisplayData(
                data.jsonData,
                data.jsonMetadata,
                Execute.DisplayData.Transient(data.idOpt)
              )
              firstMessage.publish(
                Execute.updateDisplayDataType,
                content,
                ident = Some(Execute.updateDisplayDataType.messageType)
              ).asRawMessage
          }
          try
            conn
              .send(Channel.Publish, msg0)
              .unsafeRunSync()(IORuntime.global)
          catch {
            case NonFatal(e) =>
              throw new Exception(e)
          }
        }
      }
    }
  thread.start()
  def stdout(s: String): Unit =
    queue.add(Stdout(s))
  def stderr(s: String): Unit =
    queue.add(Stderr(s))
  def display(displayData: almond.interpreter.api.DisplayData): Unit =
    queue.add(Display(displayData))
  def updateDisplay(displayData: almond.interpreter.api.DisplayData): Unit =
    queue.add(UpdateDisplay(displayData))
  def canOutput(): Boolean = true

  def messageIdOpt: Option[String] = None
}
