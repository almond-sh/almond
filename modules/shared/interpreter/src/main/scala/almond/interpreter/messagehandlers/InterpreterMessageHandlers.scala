package almond.interpreter.messagehandlers

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.input.InputHandler
import almond.interpreter.messagehandlers.MessageHandler.blocking
import almond.interpreter.util.DisplayDataOps._
import almond.interpreter.{ExecuteResult, IOInterpreter, Message}
import almond.logger.LoggerContext
import almond.protocol._
import cats.effect.IO
import cats.syntax.apply._
import fs2.async.mutable.Queue
import fs2.Stream

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

final case class InterpreterMessageHandlers(
  interpreter: IOInterpreter,
  commHandlerOpt: Option[CommHandler],
  inputHandlerOpt: Option[InputHandler],
  queueEc: ExecutionContext,
  logCtx: LoggerContext,
  runAfterQueued: IO[Unit] => IO[Unit]
) {

  import InterpreterMessageHandlers._


  def executeHandler: MessageHandler =
    blocking(Channel.Requests, Execute.requestType, queueEc, logCtx) { (message, queue) =>

      val handler = new QueueOutputHandler(message, queue, commHandlerOpt)

      lazy val inputManagerOpt = inputHandlerOpt.map { h =>
        h.inputManager(message, (c, m) => queue.enqueue1((c, m)))
      }

      // TODO Take message.content.silent into account

      // TODO Decode and take into account message.content.user_expressions?

      for {
        countBefore <- interpreter.executionCount
        inputMessage = Execute.Input(
          execution_count = countBefore + 1,
          code = message.content.code
        )
        _ <- message
          .publish(Execute.inputType, inputMessage)
          .enqueueOn(Channel.Publish, queue)
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
              .enqueueOn(Channel.Publish, queue)
          case e: ExecuteResult.Error =>
            val extra =
              if (message.content.stop_on_error.getOrElse(false))
                interpreter.cancelledSignal.set(true) *> runAfterQueued(interpreter.cancelledSignal.set(false))
              else
                IO.unit
            val error = Execute.Error("", "", List(e.message))
            extra *>
              message
                .publish(Execute.errorType, error)
                .enqueueOn(Channel.Publish, queue)
          case ExecuteResult.Abort =>
            IO.unit
        }
        resp = res match {
          case v: ExecuteResult.Success =>
            Execute.Reply.Success(countAfter, v.data.jsonData)
          case ex: ExecuteResult.Error =>
            val traceBack = Seq(ex.name, ex.message).filter(_.nonEmpty).mkString(": ") :: ex.stackTrace.map("    " + _)
            Execute.Reply.Error(ex.name, ex.message, traceBack /* or just stackTrace? */, countAfter)
          case ExecuteResult.Abort =>
            Execute.Reply.Abort()
        }
        _ <- message
          .reply(Execute.replyType, resp)
          .enqueueOn(Channel.Requests, queue)
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
          Map()
        )
        _ <- message
          .reply(Complete.replyType, reply)
          .enqueueOn(Channel.Requests, queue)
      } yield ()
    }


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
        resOpt <- interpreter.inspect(message.content.code, message.content.cursor_pos, message.content.detail_level)
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
    blocking(Channel.Requests, KernelInfo.requestType, queueEc, logCtx) { (message, queue) =>

      for {
        info <- interpreter.kernelInfo
        _ <- message
          .reply(KernelInfo.replyType, info)
          .enqueueOn(Channel.Requests, queue)
      } yield ()
    }


  def handler: MessageHandler =
    kernelInfoHandler.orElse(
      executeHandler,
      completeHandler,
      isCompleteHandler,
      inspectHandler,
      historyHandler
    )

  def shutdownHandler: MessageHandler = {
    // v5.3 spec states "The request can be sent on either the control or shell channels.".
    MessageHandler(Set(Channel.Control, Channel.Requests), Shutdown.requestType) { case (channel, message) =>
      val reply = {
        val msg = message.reply(Shutdown.replyType, Shutdown.Reply(message.content.restart))
        msg.streamOn(channel)
      }

      // Allow some time for message to be delivered. Doing this on the `queueEc` isn't great in that shutdown could
      // hypothetically be sequenced behind queue operations, but in practice there's likely no consequence.
      // Alternatively we could Thread.sleep() sleep inside a regular/synchronous IO but then might want to construct
      // our Stream Chunks by hand to avoid sleep() causing any delay in delivery of our reply.
      val delayedShutdown = IO.timer(queueEc).sleep(500.milliseconds).flatMap(_ => interpreter.shutdown)

      reply ++ Stream.eval(delayedShutdown)
    }
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
    queue: Queue[IO, (Channel, RawMessage)],
    commHandlerOpt: Option[CommHandler]
  ) extends OutputHandler {

    private def print(on: String, s: String): Unit =
      message
        .publish(Execute.streamType, Execute.Stream(name = on, text = s), ident = Some(on))
        .enqueueOn(Channel.Publish, queue)
        .unsafeRunSync()

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
        .enqueueOn(Channel.Publish, queue)
        .unsafeRunSync()
    }

    def updateDisplay(displayData: DisplayData): Unit =
      // Using the commHandler rather than pushing a message through our own queue, so that
      // messages sent after the originating cell is done running, are still sent to the client.
      // TODO Warn if no commHandler is available
      commHandlerOpt.foreach(_.updateDisplay(displayData))
  }

}
