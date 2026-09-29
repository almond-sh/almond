package almond.interpreter.messagehandlers

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.api.{CommHandler, DisplayData, ExecuteResult, OutputHandler}
import almond.interpreter.input.InputHandler
import almond.interpreter.messagehandlers.MessageHandler.{blocking, blocking0, blockingWithStatus}
import almond.interpreter.util.DisplayDataOps._
import almond.interpreter.{IOInterpreter, Message}
import almond.logger.LoggerContext
import almond.protocol._
import almond.protocol.Codecs.unitCodec
import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime
import cats.syntax.apply._
import fs2.concurrent.SignallingRef

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

final case class InterpreterMessageHandlers(
  interpreter: IOInterpreter,
  commHandlerOpt: Option[CommHandler],
  inputHandlerOpt: Option[InputHandler],
  queueEc: ExecutionContext,
  logCtx: LoggerContext,
  runAfterQueued: IO[Unit] => IO[Unit],
  exitSignal: SignallingRef[IO, Boolean],
  noExecuteInputFor: Set[String],
  ioRuntime: IORuntime
) {

  import com.github.plokhotnyuk.jsoniter_scala.core._
  import InterpreterMessageHandlers._

  /** Number of execute requests being processed.
    *
    * Other requests (completions, inspections, …) can be processed while an execute request is
    * being processed. In that case, these don't publish busy / idle statuses: the kernel is already
    * reported as busy because of the execute request, and publishing an idle status once they're
    * done would wrongly report it as idle while the cell is still running.
    */
  private val executingCount = new AtomicInteger

  private def isExecuting: IO[Boolean] =
    IO(executingCount.get() > 0)

  /** Handles messages on the shell channel, that don't publish statuses while a cell is running */
  private def shellHandler[T: JsonValueCodec](messageType: MessageType[T])(
    handler: (Message[T], Queue[IO, (Channel, RawMessage)]) => IO[Unit]
  ): MessageHandler =
    blockingWithStatus(
      Set(Channel.Requests),
      messageType,
      queueEc,
      logCtx,
      _ => isExecuting.map(!_)
    ) { (_, message, queue) =>
      handler(message, queue)
    }

  def executeHandler: MessageHandler =
    blocking0(Channel.Requests, Execute.requestType, queueEc, logCtx) {
      (rawMessage, message, queue) =>
        val main = executeMain(rawMessage, message, queue)
        IO(executingCount.incrementAndGet())
          .bracket(_ => main)(_ => IO(executingCount.decrementAndGet()).void)
    }

  private def executeMain(
    rawMessage: RawMessage,
    message: Message[Execute.Request],
    queue: Queue[IO, Either[Throwable, (Channel, RawMessage)]]
  ): IO[Unit] = {

    val payloads = new ListBuffer[String]
    val handler  = new QueueOutputHandler(message, queue, commHandlerOpt, payloads, ioRuntime)

    def payloadsAsJson(): List[RawJson] =
      payloads.toList.map { str =>
        readFromString(str)(RawJson.codec)
      }

    lazy val inputManagerOpt = inputHandlerOpt.map { h =>
      h.inputManager(message, (c, m) => queue.offer(Right((c, m))))
    }

    // silent requests must not publish execute_input / execute_result, and must not
    // store history nor increment the execution count
    val silent       = message.content.silent.getOrElse(false)
    val storeHistory = !silent && message.content.store_history.getOrElse(true)

    // TODO Evaluate message.content.user_expressions, and send their results in the reply
    // (these are empty for now, the result of the cell is only sent via execute_result)

    for {
      // Set if a former cell failed with stop_on_error. This can't be reset while we're processing
      // this message, as the reset is run on the execute queue, after the cells queued so far.
      aborted     <- interpreter.cancelledSignal.get
      countBefore <- interpreter.executionCount
      inputMessage = Execute.Input(
        execution_count = countBefore + 1,
        code = message.content.code
      )
      _ <- {
        // no execute_input for silent requests, nor for cells that we don't run
        if (silent || aborted || noExecuteInputFor.contains(message.header.msg_id))
          IO.unit
        else
          message
            .publish(Execute.inputType, inputMessage)
            .enqueueOn0(Channel.Publish, queue)
      }
      res <- {
        if (aborted)
          IO.pure(ExecuteResult.Abort)
        else
          interpreter.execute(
            message.content.code,
            storeHistory,
            if (message.content.allow_stdin.getOrElse(true)) inputManagerOpt else None,
            Some(handler),
            Some(message)
          )
      }
      countAfter <- interpreter.executionCount
      _ <- res match {
        case v: ExecuteResult.Success if silent || v.data.isEmpty =>
          IO.unit
        case v: ExecuteResult.Success =>
          val result = Execute.Result(
            countAfter,
            v.data.jsonData,
            Map.empty,
            transient = Execute.DisplayData.Transient(v.data.idOpt)
          )
          message
            .publish(Execute.resultType, result)
            .enqueueOn0(Channel.Publish, queue)
        case e: ExecuteResult.Error =>
          val extra =
            if (message.content.stop_on_error.getOrElse(true))
              interpreter.cancelledSignal.set(true) *>
                runAfterQueued(interpreter.cancelledSignal.set(false))
            else
              IO.unit
          val error = Execute.Error(e.name, e.message, e.stackTrace)
          extra *>
            message
              .publish(Execute.errorType, error)
              .enqueueOn0(Channel.Publish, queue)
        case ExecuteResult.Abort =>
          IO.unit
        case ExecuteResult.Exit =>
          exitSignal.set(true)
        case ExecuteResult.Close =>
          IO.unit
      }
      respOpt = res match {
        case v: ExecuteResult.Success =>
          Right(Execute.Reply.Success(countAfter, Map.empty, payload = payloadsAsJson()))
        case ex: ExecuteResult.Error =>
          val traceBack =
            Seq(ex.name, ex.message)
              .filter(_.nonEmpty)
              .mkString(": ") ::
              ex.stackTrace.map("    " + _)
          val r = Execute.Reply.Error(
            ex.name,
            ex.message,
            traceBack /* or just stackTrace? */,
            countAfter
          )
          Right(r)
        case ExecuteResult.Abort =>
          Right(Execute.Reply.Abort(countAfter))
        case ExecuteResult.Exit =>
          val payload = Execute.Reply.Success.AskExitPayload("ask_exit", false)
          Right(Execute.Reply.Success(countAfter, Map(), List(RawJson(writeToArray(payload)))))
        case ExecuteResult.Close =>
          Left(new CloseExecutionException(Seq((Channel.Requests, rawMessage))))
      }
      _ <- {
        respOpt match {
          case Right(resp) =>
            message
              .reply(Execute.replyType, resp)
              .enqueueOn0(Channel.Requests, queue)
          case Left(e) =>
            queue.offer(Left(e))
        }
      }
    } yield ()
  }

  def completeHandler: MessageHandler =
    shellHandler(Complete.requestType) { (message, queue) =>

      val code = message.content.code
      for {
        res <- interpreter.complete(code, codePointToCharIndex(code, message.content.cursor_pos))
        reply = Complete.Reply(
          res.completions.toList,
          charIndexToCodePoint(code, res.from),
          charIndexToCodePoint(code, res.until),
          res.metadata
        )
        _ <- message
          .reply(Complete.replyType, reply)
          .enqueueOn(Channel.Requests, queue)
      } yield ()
    }

  def otherHandlers: MessageHandler =
    kernelInfoHandler.orElse(
      completeHandler,
      interruptHandler,
      shutdownHandler,
      isCompleteHandler,
      inspectHandler,
      historyHandler
    )

  def isCompleteHandler: MessageHandler =
    shellHandler(IsComplete.requestType) { (message, queue) =>

      for {
        res <- interpreter.isComplete(message.content.code)
        _ <- message
          .reply(
            IsComplete.replyType,
            res.fold(IsComplete.Reply("unknown"))(c => IsComplete.Reply(c.status))
          )
          .enqueueOn(Channel.Requests, queue)
      } yield ()
    }

  def inspectHandler: MessageHandler =
    shellHandler(Inspect.requestType) { (message, queue) =>

      val code = message.content.code
      for {
        resOpt <- interpreter.inspect(
          code,
          codePointToCharIndex(code, message.content.cursor_pos),
          message.content.detail_level
        )
        reply = Inspect.Reply(
          found = resOpt.nonEmpty,
          data = resOpt.map(_.data).getOrElse(Map.empty),
          metadata = resOpt.map(_.metadata).getOrElse(Map.empty)
        )
        _ <- message
          .reply(Inspect.replyType, reply)
          .enqueueOn(Channel.Requests, queue)
      } yield ()
    }

  def historyHandler: MessageHandler =
    shellHandler(History.requestType) { (message, queue) =>
      // for now, always sending back an empty response
      message
        .reply(History.replyType, History.Reply.Simple(Nil))
        .enqueueOn(Channel.Requests, queue)
    }

  def kernelInfoHandler: MessageHandler =
    // Protocol 5.5 allows this request on both channels. Like other requests, it publishes
    // busy / idle statuses on both of them, unless a cell is running.
    blockingWithStatus(
      Set(Channel.Requests, Channel.Control),
      MessageType[Unit](KernelInfo.requestType.messageType),
      queueEc,
      logCtx,
      publishStatus = _ => isExecuting.map(!_)
    ) { (channel, message, queue) =>

      for {
        info <- interpreter.kernelInfo
        _ <- message
          .reply(KernelInfo.replyType, info)
          .enqueueOn(channel, queue)
      } yield ()
    }

  def shutdownHandler: MessageHandler =
    // v5.3 spec states "The request can be sent on either the control or shell channels.".
    blockingWithStatus(
      Set(Channel.Control, Channel.Requests),
      Shutdown.requestType,
      queueEc,
      logCtx,
      publishStatus = _ => isExecuting.map(!_)
    ) { (channel, message, queue) =>

      for {
        _ <- exitSignal.set(true)
        _ <- interpreter.shutdown
        _ <- message
          .reply(Shutdown.replyType, Shutdown.Reply(message.content.restart))
          .enqueueOn(channel, queue)
      } yield ()
    }

  def interruptHandler: MessageHandler =
    blocking(Channel.Control, Interrupt.requestType, queueEc, logCtx) { (message, queue) =>

      for {
        _ <- interpreter.interrupt
        _ <- message
          .reply(Interrupt.replyType, Interrupt.Reply())
          .enqueueOn(Channel.Control, queue)
      } yield ()
    }

}

object InterpreterMessageHandlers {

  // Jupyter protocol >= 5.2 cursor positions are offsets in unicode code points, while
  // interpreters work with Java String indices (UTF-16 code units). These differ as soon
  // as code contains characters outside of the BMP (emojis, some mathematical letters, …).
  // Positions out of the bounds of the string are shifted as is.

  private[almond] def codePointToCharIndex(s: String, codePointIdx: Int): Int =
    if (codePointIdx <= 0) codePointIdx
    else {
      val count = s.codePointCount(0, s.length)
      if (codePointIdx >= count) s.length + (codePointIdx - count)
      else s.offsetByCodePoints(0, codePointIdx)
    }

  private[almond] def charIndexToCodePoint(s: String, charIdx: Int): Int =
    if (charIdx <= 0) charIdx
    else if (charIdx >= s.length) s.codePointCount(0, s.length) + (charIdx - s.length)
    else s.codePointCount(0, charIdx)

  private final class QueueOutputHandler(
    message: Message[_],
    queue: Queue[IO, Either[Throwable, (Channel, RawMessage)]],
    commHandlerOpt: Option[CommHandler],
    payloads: ListBuffer[String],
    ioRuntime: IORuntime
  ) extends OutputHandler {

    private def print(on: String, s: String): Unit =
      message
        .publish(Execute.streamType, Execute.Stream(name = on, text = s), ident = Some(on))
        .enqueueOn0(Channel.Publish, queue)
        .unsafeRunSync()(ioRuntime)

    def stdout(s: String): Unit =
      print("stdout", s)
    def stderr(s: String): Unit =
      print("stderr", s)

    def display(data: DisplayData): Unit = {

      val content = Execute.DisplayData(
        data.jsonData,
        data.jsonMetadata,
        Execute.DisplayData.Transient(data.idOpt)
      )

      message
        .publish(Execute.displayDataType, content)
        .enqueueOn0(Channel.Publish, queue)
        .unsafeRunSync()(ioRuntime)
    }

    def updateDisplay(displayData: DisplayData): Unit =
      // Using the commHandler rather than pushing a message through our own queue, so that
      // messages sent after the originating cell is done running, are still sent to the client.
      // TODO Warn if no commHandler is available
      commHandlerOpt.foreach(_.updateDisplay(displayData))

    def canOutput(): Boolean = true

    def messageIdOpt: Option[String] = Some(message.header.msg_id)

    def addPayload(payload: String): Unit =
      payloads += payload
  }

}
