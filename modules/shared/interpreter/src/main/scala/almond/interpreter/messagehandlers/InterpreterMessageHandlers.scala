package almond.interpreter.messagehandlers

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.input.InputHandler
import almond.interpreter.messagehandlers.MessageHandler.{blocking, blocking0}
import almond.interpreter.util.DisplayDataOps._
import almond.interpreter.{ExecuteResult, IOInterpreter, Message}
import almond.logger.LoggerContext
import almond.protocol._
import almond.protocol.Codecs.unitCodec
import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime
import cats.syntax.apply._
import fs2.Stream
import fs2.concurrent.SignallingRef

import scala.concurrent.ExecutionContext

final case class InterpreterMessageHandlers(
  interpreter: IOInterpreter,
  commHandlerOpt: Option[CommHandler],
  inputHandlerOpt: Option[InputHandler],
  queueEc: ExecutionContext,
  logCtx: LoggerContext,
  runAfterQueued: IO[Unit] => IO[Unit],
  exitSignal: SignallingRef[IO, Boolean],
  noExecuteInputFor: Set[String]
) {

  import com.github.plokhotnyuk.jsoniter_scala.core._
  import InterpreterMessageHandlers._

  def executeHandler: MessageHandler =
    blocking0(Channel.Requests, Execute.requestType, queueEc, logCtx) {
      (rawMessage, message, queue) =>

        val handler = new QueueOutputHandler(message, queue, commHandlerOpt)

        lazy val inputManagerOpt = inputHandlerOpt.map { h =>
          h.inputManager(message, (c, m) => queue.offer(Right((c, m))))
        }

        // TODO Take message.content.silent into account

        // TODO Decode and take into account message.content.user_expressions?

        for {
          countBefore <- interpreter.executionCount
          inputMessage = Execute.Input(
            execution_count = countBefore + 1,
            code = message.content.code
          )
          _ <- {
            if (noExecuteInputFor.contains(message.header.msg_id))
              IO.unit
            else
              message
                .publish(Execute.inputType, inputMessage)
                .enqueueOn0(Channel.Publish, queue)
          }
          res <- interpreter.execute(
            message.content.code,
            message.content.store_history.getOrElse(true),
            if (message.content.allow_stdin.getOrElse(true)) inputManagerOpt else None,
            Some(handler)
          )
          countAfter <- interpreter.executionCount
          _ <- res match {
            case v: ExecuteResult.Success if v.data.isEmpty =>
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
                if (message.content.stop_on_error.getOrElse(false))
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
              Right(Execute.Reply.Success(countAfter, v.data.jsonData))
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
              Right(Execute.Reply.Abort())
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
    blocking(Channel.Requests, Complete.requestType, queueEc, logCtx) { (message, queue) =>

      for {
        res <- interpreter.complete(message.content.code, message.content.cursor_pos)
        reply = Complete.Reply(
          res.completions.toList,
          res.from,
          res.until,
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
    blocking(Channel.Requests, IsComplete.requestType, queueEc, logCtx) { (message, queue) =>

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
    blocking(Channel.Requests, Inspect.requestType, queueEc, logCtx) { (message, queue) =>

      for {
        resOpt <- interpreter.inspect(
          message.content.code,
          message.content.cursor_pos,
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
    blocking(Channel.Requests, History.requestType, queueEc, logCtx) { (message, queue) =>
      // for now, always sending back an empty response
      message
        .reply(History.replyType, History.Reply.Simple(Nil))
        .enqueueOn(Channel.Requests, queue)
    }

  def kernelInfoHandler: MessageHandler =
    blocking(
      Channel.Requests,
      MessageType[Unit](KernelInfo.requestType.messageType),
      queueEc,
      logCtx
    ) { (message, queue) =>

      for {
        info <- interpreter.kernelInfo
        _ <- message
          .reply(KernelInfo.replyType, info)
          .enqueueOn(Channel.Requests, queue)
      } yield ()
    }

  def shutdownHandler: MessageHandler =
    // v5.3 spec states "The request can be sent on either the control or shell channels.".
    MessageHandler(Set(Channel.Control, Channel.Requests), Shutdown.requestType) {
      (channel, message) =>

        val reply = message
          .reply(Shutdown.replyType, Shutdown.Reply(message.content.restart))
          .streamOn(channel)

        val prepareShutdown = exitSignal
          .set(true)
          .flatMap(_ => interpreter.shutdown)

        Stream.eval(prepareShutdown)
          .flatMap(_ => reply)
    }

  def interruptHandler: MessageHandler =
    blocking(Channel.Control, Interrupt.requestType, queueEc, logCtx) { (message, queue) =>

      for {
        _ <- interpreter.interrupt
        _ <- message
          .reply(Interrupt.replyType, Interrupt.Reply)
          .enqueueOn(Channel.Control, queue)
      } yield ()
    }

}

object InterpreterMessageHandlers {

  private final class QueueOutputHandler(
    message: Message[_],
    queue: Queue[IO, Either[Throwable, (Channel, RawMessage)]],
    commHandlerOpt: Option[CommHandler]
  ) extends OutputHandler {

    private def print(on: String, s: String): Unit =
      message
        .publish(Execute.streamType, Execute.Stream(name = on, text = s), ident = Some(on))
        .enqueueOn0(Channel.Publish, queue)
        .unsafeRunSync()(IORuntime.global)

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
        .unsafeRunSync()(IORuntime.global)
    }

    def updateDisplay(displayData: DisplayData): Unit =
      // Using the commHandler rather than pushing a message through our own queue, so that
      // messages sent after the originating cell is done running, are still sent to the client.
      // TODO Warn if no commHandler is available
      commHandlerOpt.foreach(_.updateDisplay(displayData))

    def canOutput(): Boolean = true

    def messageIdOpt: Option[String] = Some(message.header.msg_id)
  }

}
