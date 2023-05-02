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
import fs2.concurrent.{Queue, SignallingRef}
import fs2.{Pipe, Stream}

import scala.concurrent.ExecutionContext

final case class Kernel(
  interpreter: IOInterpreter,
  backgroundMessagesQueue: Queue[IO, (Channel, RawMessage)],
  mainQueue: Queue[IO, Option[Stream[IO, (Channel, RawMessage)]]],
  backgroundCommHandlerOpt: Option[DefaultCommHandler],
  inputHandler: InputHandler,
  kernelThreads: KernelThreads,
  logCtx: LoggerContext,
  extraHandler: MessageHandler
) {

  private lazy val log = logCtx(getClass)

  def replies(requests: Stream[IO, (Channel, RawMessage)]): Stream[IO, (Channel, RawMessage)] = {

    val exitSignal = {
      implicit val shift = IO.contextShift(kernelThreads.scheduleEc) // or another ExecutionContext?
      SignallingRef[IO, Boolean](false)
    }

    Stream.eval(exitSignal).flatMap { exitSignal0 =>

      val interpreterMessageHandler = InterpreterMessageHandlers(
        interpreter,
        backgroundCommHandlerOpt,
        Some(inputHandler),
        kernelThreads.queueEc,
        logCtx,
        io => mainQueue.enqueue1(Some(Stream.eval_(io))),
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
          Stream.eval_(attemptInit) ++
          sendStatus(Status.idle)
      }

      val mainStream = {

        val requests0 = {
          implicit val shift = IO.contextShift(kernelThreads.scheduleEc)
          requests.interruptWhen(exitSignal0)
        }

        // For each incoming message, an IO that processes it, and gives the response messages
        val streams: Stream[IO, Stream[IO, (Channel, RawMessage)]] =
          requests0.map {
            case (channel, rawMessage) =>
              interpreterMessageHandler.handler.handleOrLogError(channel, rawMessage, log) match {
                case None =>
                  // interpreter message handler passes, try with the other handlers

                  immediateHandlers.handleOrLogError(channel, rawMessage, log) match {
                    case None =>
                      log.warn(s"Ignoring unhandled message on $channel:\n$rawMessage")
                      Stream.empty

                    case Some(output) =>
                      // process stdin messages and send response back straightaway
                      output
                  }

                case Some(output) =>
                  // enqueue stream that processes the incoming message, so that the main messages are
                  // still processed and answered in order
                  Stream.eval_(mainQueue.enqueue1(Some(output)))
              }
          }

        // Try to process messages eagerly, to e.g. process comm messages even while an execute_request is
        // being processed.
        // Order of the main messages and their answers is still preserved via mainQueue, see also comments above.
        val mergedStreams = {
          implicit val shift = IO.contextShift(kernelThreads.scheduleEc)
          streams.parJoin(20) // https://twitter.com/mpilquist/status/943653692745666560
        }

        // Put poison pill (null) at the end of mainQueue when all input messages have been processed
        val s1 = Stream.bracket(IO.unit)(_ => mainQueue.enqueue1(None))
          .flatMap(_ => mergedStreams)

        // Reponses for the main messages
        val s2 = mainQueue
          .dequeue
          .takeWhile(_.nonEmpty)
          .flatMap(s => s.getOrElse[Stream[IO, (Channel, RawMessage)]](Stream.empty))

        // Merge s1 (messages answered straightaway and enqueuing of the main messages) and s2 (responses of main
        // messages, that are processed sequentially via mainQueue)
        {
          implicit val shift = IO.contextShift(kernelThreads.scheduleEc)
          s1.merge(s2)
        }
      }

      // Put poison pill (null) at the end of backgroundMessagesQueue when all input messages have been processed
      // and answered.
      val mainStream0 = Stream.bracket(IO.unit)(_ => backgroundMessagesQueue.enqueue1(null))
        .flatMap(_ => initStream ++ mainStream)

      // Merge responses to all incoming messages with background messages (comm messages sent by user code when it
      // is run)
      {
        implicit val shift = IO.contextShift(kernelThreads.scheduleEc)
        mainStream0.merge(backgroundMessagesQueue.dequeue.takeWhile(_ != null))
      }
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
      backgroundMessagesQueue <- {
        implicit val shift = IO.contextShift(kernelThreads.queueEc)
        Queue.bounded[IO, (Channel, RawMessage)](20) // FIXME Sizing
      }
      mainQueue <- {
        implicit val shift = IO.contextShift(kernelThreads.queueEc)
        Queue.bounded[IO, Option[Stream[IO, (Channel, RawMessage)]]](50) // FIXME Sizing
      }
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
      backgroundCommHandlerOpt,
      inputHandler,
      kernelThreads,
      logCtx,
      extraHandler
    )

}
