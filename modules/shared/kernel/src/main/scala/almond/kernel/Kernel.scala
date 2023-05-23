package almond.kernel

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import almond.channels.zeromq.ZeromqThreads
import almond.channels.{Channel, ConnectionParameters, Message => RawMessage}
import almond.interpreter.{IOInterpreter, Interpreter, InterpreterToIOInterpreter, Message}
import almond.interpreter.comm.DefaultCommHandler
import almond.interpreter.input.InputHandler
import almond.interpreter.messagehandlers.{
  CommMessageHandlers,
  InterpreterMessageHandlers,
  MessageHandler
}
import almond.logger.LoggerContext
import almond.protocol.{Header, Protocol, Status, Connection => JsonConnection}
import cats.effect.IO
import cats.effect.std.Queue
import fs2.concurrent.SignallingRef
import fs2.{Pipe, Stream}

import scala.concurrent.ExecutionContext

final case class Kernel(
  interpreter: IOInterpreter,
  backgroundMessagesQueue: Queue[IO, (Channel, RawMessage)],
  mainQueue: Queue[IO, Option[Stream[IO, (Channel, RawMessage)]]],
  otherQueue: Queue[IO, Option[Stream[IO, (Channel, RawMessage)]]],
  backgroundCommHandlerOpt: Option[DefaultCommHandler],
  inputHandler: InputHandler,
  kernelThreads: KernelThreads,
  logCtx: LoggerContext,
  extraHandler: MessageHandler
) {

  private lazy val log = logCtx(getClass)

  def replies(requests: Stream[IO, (Channel, RawMessage)]): Stream[IO, (Channel, RawMessage)] = {

    val exitSignal = SignallingRef[IO, Boolean](false)

    Stream.eval(exitSignal).flatMap { exitSignal0 =>

      val interpreterMessageHandler = InterpreterMessageHandlers(
        interpreter,
        backgroundCommHandlerOpt,
        Some(inputHandler),
        kernelThreads.queueEc,
        logCtx,
        io => mainQueue.offer(Some(Stream.exec(io))),
        exitSignal0
      )

      val commMessageHandler = backgroundCommHandlerOpt match {
        case None =>
          MessageHandler.empty
        case Some(commHandler) =>
          CommMessageHandlers(commHandler.commTargetManager, kernelThreads.queueEc, logCtx)
            .messageHandler
      }

      // handlers whose messages are processed straightaway (no queueing to enforce sequential processing)
      val immediateHandlers = inputHandler.messageHandler
        .orElse(interpreterMessageHandler.completeHandler)
        .orElse(commMessageHandler)
        .orElse(interpreterMessageHandler.interruptHandler)
        .orElse(interpreterMessageHandler.shutdownHandler)
        .orElse(extraHandler)

      // for w/e reason, these seem not to be processed on time by the Jupyter classic UI
      // (don't know about lab, nteract seems fine, unless it just marks kernels as starting by itself)
      val initStream = {

        def sendStatus(status: Status) =
          Stream(
            Message(
              Header(
                UUID.randomUUID().toString,
                "username",
                UUID.randomUUID().toString, // Would there be a way to get the session id from the client?
                Status.messageType.messageType,
                Some(Protocol.versionStr)
              ),
              Status.starting,
              idents = List(Status.messageType.messageType.getBytes(UTF_8).toSeq)
            ).on(Channel.Publish)
          )

        val attemptInit = interpreter.init.attempt.flatMap { a =>

          for (e <- a.left)
            log.error("Error initializing interpreter", e)

          IO.fromEither(a)
        }

        sendStatus(Status.starting) ++
          sendStatus(Status.busy) ++
          Stream.exec(attemptInit) ++
          sendStatus(Status.idle)
      }

      val mainStream = {

        val requests0 = requests.interruptWhen(exitSignal0)

        // For each incoming message, an IO that processes it, and gives the response messages
        val scatterMessages: Stream[IO, Unit] =
          requests0.evalMap {
            case (channel, rawMessage) =>
              interpreterMessageHandler.handler.handleOrLogError(channel, rawMessage, log) match {
                case None =>
                  // interpreter message handler passes, try with the other handlers

                  immediateHandlers.handleOrLogError(channel, rawMessage, log) match {
                    case None =>
                      log.warn(s"Ignoring unhandled message on $channel:\n$rawMessage")
                      IO.unit

                    case Some(output) =>
                      // process stdin messages and send response back straightaway
                      otherQueue.offer(Some(output))
                  }

                case Some(output) =>
                  // enqueue stream that processes the incoming message, so that the main messages are
                  // still processed and answered in order
                  mainQueue.offer(Some(output))
              }
          }

        // Put poison pill (null) at the end of mainQueue when all input messages have been scattered
        val s1: Stream[IO, Nothing] =
          Stream.bracket(IO.unit)(_ => mainQueue.offer(None).flatMap(_ => otherQueue.offer(None)))
            .flatMap(_ => Stream.exec(scatterMessages.compile.drain))

        // Reponses for the main messages
        val s2 = Stream.repeatEval(mainQueue.take)
          .takeWhile(_.nonEmpty)
          .flatMap(s => s.getOrElse[Stream[IO, (Channel, RawMessage)]](Stream.empty))

        // Reponses for the other messages
        val s3 = Stream.repeatEval(otherQueue.take)
          .takeWhile(_.nonEmpty)
          .flatMap(s => s.getOrElse[Stream[IO, (Channel, RawMessage)]](Stream.empty))

        // Merge s1 (messages scattered straightaway), s2 (responses of main messages, that are processed sequentially
        // via mainQueue), and s3 (responses of other messages, that are processed in parallel)
        s1.merge(s2).merge(s3)
      }

      // Put poison pill (null) at the end of backgroundMessagesQueue when all input messages have been processed
      // and answered.
      val mainStream0 = Stream.bracket(IO.unit)(_ => backgroundMessagesQueue.offer(null))
        .flatMap(_ => initStream ++ mainStream)

      // Merge responses to all incoming messages with background messages (comm messages sent by user code when it
      // is run)
      mainStream0.merge(Stream.repeatEval(backgroundMessagesQueue.take).takeWhile(_ != null))
    }
  }

  def run(
    stream: Stream[IO, (Channel, RawMessage)],
    sink: Pipe[IO, (Channel, RawMessage), Unit]
  ): IO[Unit] =
    sink(replies(stream)).compile.drain

  def runOnConnection(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads
  ): IO[Unit] =
    for {
      c <- connection.channels(
        bind = true,
        zeromqThreads,
        logCtx,
        identityOpt = Some(kernelId)
      )
      _ <- c.open
      _ <- run(c.stream(), c.autoCloseSink)
    } yield ()

  def runOnConnectionFile(
    connectionPath: Path,
    kernelId: String,
    zeromqThreads: ZeromqThreads
  ): IO[Unit] =
    for {
      _ <- {
        if (Files.exists(connectionPath))
          IO.unit
        else
          IO.raiseError(new Exception(s"Connection file $connectionPath not found"))
      }
      _ <- {
        if (Files.isRegularFile(connectionPath))
          IO.unit
        else
          IO.raiseError(new Exception(s"Connection file $connectionPath not a regular file"))
      }
      connection <- JsonConnection.fromPath(connectionPath)
      _ <- runOnConnection(
        connection.connectionParameters,
        kernelId,
        zeromqThreads
      )
    } yield ()

  def runOnConnectionFile(
    connectionPath: String,
    kernelId: String,
    zeromqThreads: ZeromqThreads
  ): IO[Unit] =
    runOnConnectionFile(
      Paths.get(connectionPath),
      kernelId,
      zeromqThreads
    )

}

object Kernel {

  def create(
    interpreter: Interpreter,
    interpreterEc: ExecutionContext,
    kernelThreads: KernelThreads,
    logCtx: LoggerContext,
    extraHandler: MessageHandler
  ): IO[Kernel] =
    create(
      new InterpreterToIOInterpreter(interpreter, interpreterEc, logCtx),
      kernelThreads,
      logCtx,
      extraHandler
    )

  def create(
    interpreter: Interpreter,
    interpreterEc: ExecutionContext,
    kernelThreads: KernelThreads,
    logCtx: LoggerContext = LoggerContext.nop
  ): IO[Kernel] =
    create(
      interpreter,
      interpreterEc,
      kernelThreads,
      logCtx,
      MessageHandler.empty
    )

  def create(
    interpreter: IOInterpreter,
    kernelThreads: KernelThreads,
    logCtx: LoggerContext,
    extraHandler: MessageHandler
  ): IO[Kernel] =
    for {
      backgroundMessagesQueue <- Queue.bounded[IO, (Channel, RawMessage)](20) // FIXME Sizing
      mainQueue  <- Queue.bounded[IO, Option[Stream[IO, (Channel, RawMessage)]]](50) // FIXME Sizing
      otherQueue <- Queue.bounded[IO, Option[Stream[IO, (Channel, RawMessage)]]](50) // FIXME Sizing
      backgroundCommHandlerOpt <- IO {
        if (interpreter.supportComm)
          Some {
            val h = new DefaultCommHandler(backgroundMessagesQueue, kernelThreads.commEc)
            interpreter.setCommHandler(h)
            h
          }
        else
          None
      }
      inputHandler <- IO {
        new InputHandler(kernelThreads.futureEc, logCtx)
      }
    } yield Kernel(
      interpreter,
      backgroundMessagesQueue,
      mainQueue,
      otherQueue,
      backgroundCommHandlerOpt,
      inputHandler,
      kernelThreads,
      logCtx,
      extraHandler
    )

}
