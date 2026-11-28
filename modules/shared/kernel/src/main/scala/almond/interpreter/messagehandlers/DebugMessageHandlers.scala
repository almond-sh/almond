package almond.interpreter.messagehandlers

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.{Debugger, KernelSession, Message}
import almond.interpreter.util.Uninterruptible
import almond.logger.LoggerContext
import almond.protocol.{Debug, Header, RawJson}
import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext

/** Handles the messages of the Jupyter debugger protocol
  *
  * `debug_request` messages, on the control channel, are passed to `debugger`, and its responses
  * are sent back as `debug_reply` messages. The DAP events of `debugger` are published on IOPub as
  * `debug_event` messages, whose parent is the last debug request.
  *
  * Unlike most other requests, these always publish busy / idle statuses, even while a cell is
  * running (or paused on a breakpoint): JupyterLab waits for the idle status of each debug request
  * before processing its reply, like ipykernel does for control requests.
  */
final class DebugMessageHandlers(
  debugger: Debugger,
  session: KernelSession,
  backgroundQueue: Queue[IO, (Channel, RawMessage)],
  queueEc: ExecutionContext,
  logCtx: LoggerContext,
  ioRuntime: IORuntime
) {

  private val log = logCtx(getClass)

  @volatile private var lastParent = Option.empty[Header]

  debugger.setEventHandler { event =>
    log.debug(s"Debug event: $event")
    val msg = Message
      .publish(session, Debug.eventType, event)
      .copy(parent_header = lastParent)
    // possibly called from threads whose interrupt flag is set
    Uninterruptible.unsafeRunSync(msg.enqueueOn(Channel.Publish, backgroundQueue))(ioRuntime)
  }

  def handler: MessageHandler =
    MessageHandler.blockingWithStatus(
      Set(Channel.Control),
      Debug.requestType,
      session,
      queueEc,
      logCtx,
      _ => IO.pure(true)
    ) { (_, message, queue) =>
      lastParent = Some(message.header)
      log.debug(s"Debug request: ${message.content}")
      for {
        resp <- IO.fromFuture(IO(debugger.request(message.content)))
        _ = log.debug(s"Debug response: $resp")
        _ <- message
          .reply(session, Debug.replyType, resp)
          .enqueueOn(Channel.Control, queue)
      } yield ()
    }
}
