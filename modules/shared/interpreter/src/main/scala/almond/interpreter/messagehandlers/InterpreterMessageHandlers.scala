package almond.interpreter.messagehandlers

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.input.InputHandler
import almond.interpreter.messagehandlers.MessageHandler.blocking
import almond.interpreter.util.DisplayDataOps._
import almond.interpreter.{ExecuteResult, IOInterpreter, Message}
import almond.protocol._
import cats.effect.IO
import cats.syntax.apply._
import fs2.async.mutable.Queue

import scala.concurrent.ExecutionContext

final case class InterpreterMessageHandlers(
  interpreter: IOInterpreter,
  commHandlerOpt: Option[CommHandler],
  inputHandlerOpt: Option[InputHandler],
  queueEc: ExecutionContext,
  runAfterQueued: IO[Unit] => IO[Unit]
) {

  import InterpreterMessageHandlers._


  def executeHandler: MessageHandler =
    blocking(Channel.Requests, Execute.requestType, queueEc) { (message, queue) =>

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
          Some(handler),
          if (message.content.allow_stdin.getOrElse(true)) inputManagerOpt else None,
          message.content.store_history.getOrElse(true),
          Some(message)
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
    blocking(Channel.Requests, Complete.requestType, queueEc) { (message, queue) =>

      for {
        res <- interpreter.complete(message.content.code, message.content.cursor_pos)
        reply = Complete.Reply(
          res.completions.toList,
          res.from,
          res.to,
          Map()
        )
        _ <- message
          .reply(Complete.replyType, reply)
          .enqueueOn(Channel.Requests, queue)
      } yield ()
    }


  def isCompleteHandler: MessageHandler =
    blocking(Channel.Requests, IsComplete.requestType, queueEc) { (message, queue) =>

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
    blocking(Channel.Requests, Inspect.requestType, queueEc) { (message, queue) =>

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


  def kernelInfoHandler: MessageHandler =
    blocking(Channel.Requests, KernelInfo.requestType, queueEc) { (message, queue) =>

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
      inspectHandler
    )

  def interruptHandler: MessageHandler =
    blocking(Channel.Control, Interrupt.requestType, queueEc) { (message, queue) =>

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
        .attempt // TODO Don't trap errors here
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
        .attempt // TODO Don't trap errors here
        .unsafeRunSync()
    }

    def updateDisplay(displayData: DisplayData): Unit =
      // Using the commHandler rather than pushing a message through our own queue, so that
      // messages sent after the originating cell is done running, are still sent to the client.
      // TODO Warn if no commHandler is available
      commHandlerOpt.foreach(_.updateDisplay(displayData))
  }

}
