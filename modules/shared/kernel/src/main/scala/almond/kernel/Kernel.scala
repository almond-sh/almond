package almond.kernel

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import almond.channels.zeromq.ZeromqThreads
import almond.channels.{Channel, Connection, ConnectionParameters, Message => RawMessage}
import almond.interpreter.{IOInterpreter, Interpreter, InterpreterToIOInterpreter, Message}
import almond.interpreter.comm.DefaultCommHandler
import almond.interpreter.input.InputHandler
import almond.interpreter.messagehandlers.{
  CloseExecutionException,
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
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.Duration

final case class Kernel(
  interpreter: IOInterpreter,
  backgroundMessagesQueue: Queue[IO, (Channel, RawMessage)],
  executeQueue: Queue[IO, Option[(
    Option[(Channel, RawMessage)],
    Stream[IO, (Channel, RawMessage)]
  )]],
  otherQueue: Queue[IO, Option[Stream[IO, (Channel, RawMessage)]]],
  backgroundCommHandlerOpt: Option[DefaultCommHandler],
  inputHandler: InputHandler,
  kernelThreads: KernelThreads,
  logCtx: LoggerContext,
  extraHandler: MessageHandler,
  noExecuteInputFor: Set[String]
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
        io => executeQueue.offer(Some(None -> Stream.exec(io))),
        exitSignal0,
        noExecuteInputFor
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
        .orElse(interpreterMessageHandler.otherHandlers)
        .orElse(commMessageHandler)
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
              status,
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
              val outputOpt = interpreterMessageHandler.executeHandler.handleOrLogError(
                channel,
                rawMessage,
                log
              )
              outputOpt match {
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
                  executeQueue.offer(Some(Some((channel, rawMessage)), output))
              }
          }

        // Put poison pill (null) at the end of executeQueue when all input messages have been scattered
        val scatterMessages0: Stream[IO, Nothing] = {
          val bracket = Stream.bracket(IO.unit) { _ =>
            executeQueue.offer(None).flatMap(_ => otherQueue.offer(None))
          }
          bracket.flatMap(_ => Stream.exec(scatterMessages.compile.drain))
        }

        // Responses for the main messages
        val executeReplies = Stream.repeatEval(executeQueue.take)
          .takeWhile(_.nonEmpty)
          .flatMap(s => s.map(_._2).getOrElse[Stream[IO, (Channel, RawMessage)]](Stream.empty))

        // Responses for the other messages
        val otherReplies = Stream.repeatEval(otherQueue.take)
          .takeWhile(_.nonEmpty)
          .flatMap(s => s.getOrElse[Stream[IO, (Channel, RawMessage)]](Stream.empty))

        // Merge scatterMessages0 (messages scattered straightaway), executeReplies (responses of execute messages, that are processed sequentially
        // via executeQueue), and otherReplies (responses of other messages, that are processed in parallel)
        scatterMessages0.merge(executeReplies).merge(otherReplies)
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
    sink: Pipe[IO, (Channel, RawMessage), Unit],
    leftoverMessages: Seq[(Channel, RawMessage)]
  ): IO[Unit] =
    sink(replies(Stream(leftoverMessages: _*) ++ stream)).compile.drain

  def runOnConnection(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    lingerDuration: Duration
  ): IO[Unit] =
    for {
      t <- runOnConnectionAllowClose0(
        connection,
        kernelId,
        zeromqThreads,
        leftoverMessages,
        autoClose = true,
        lingerDuration = lingerDuration
      )
      (run, _) = t
      _ <- run
    } yield ()

  private def runOnConnectionAllowClose0(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration
  ): IO[(IO[Unit], Connection)] =
    for {
      c <- connection.channels(
        bind = true,
        zeromqThreads,
        lingerPeriod = Some(5.minutes),
        logCtx = logCtx,
        identityOpt = Some(kernelId)
      )
    } yield {
      val run0 =
        for {
          _ <- c.open
          _ <- run(
            c.stream(),
            c.autoCloseSink(partial = !autoClose, lingerDuration = lingerDuration),
            leftoverMessages
          )
        } yield ()
      (run0, c)
    }

  private def drainExecuteMessages: IO[Seq[(Channel, RawMessage)]] =
    Stream.repeatEval(executeQueue.take)
      .takeWhile(_.nonEmpty)
      .flatMap(s => Stream(s.flatMap(_._1).toSeq: _*))
      .compile
      .toVector

  def runOnConnectionAllowClose(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration
  ): IO[(IO[Seq[(Channel, RawMessage)]], Connection)] =
    runOnConnectionAllowClose0(
      connection,
      kernelId,
      zeromqThreads,
      leftoverMessages,
      autoClose,
      lingerDuration
    ).map {
      case (run, conn) =>
        val run0 = run.attempt.flatMap {
          case Left(e: CloseExecutionException) =>
            drainExecuteMessages.map { messages =>
              e.messages ++ messages
            }
          case Left(e) =>
            IO.raiseError(e)
          case Right(()) =>
            IO.pure(Nil)
        }
        (run0, conn)
    }

  def runOnConnectionFileAllowClose(
    connectionPath: Path,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration
  ): IO[(IO[Seq[(Channel, RawMessage)]], Connection)] =
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
      value <- runOnConnectionAllowClose(
        connection.connectionParameters,
        kernelId,
        zeromqThreads,
        leftoverMessages,
        autoClose,
        lingerDuration
      )
    } yield value

  def runOnConnectionFile(
    connectionPath: Path,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration
  ): IO[Unit] =
    for {
      t <- runOnConnectionFileAllowClose(
        connectionPath,
        kernelId,
        zeromqThreads,
        leftoverMessages,
        autoClose,
        lingerDuration
      )
      (run, _) = t
      _ <- run
    } yield ()

  def runOnConnectionFile(
    connectionPath: String,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration
  ): IO[Unit] =
    for {
      t <- runOnConnectionFileAllowClose(
        connectionPath,
        kernelId,
        zeromqThreads,
        leftoverMessages,
        autoClose,
        lingerDuration
      )
      (run, _) = t
      _ <- run
    } yield ()

  def runOnConnectionFileAllowClose(
    connectionPath: String,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration
  ): IO[(IO[Seq[(Channel, RawMessage)]], Connection)] =
    runOnConnectionFileAllowClose(
      Paths.get(connectionPath),
      kernelId,
      zeromqThreads,
      leftoverMessages,
      autoClose,
      lingerDuration
    )

}

object Kernel {

  def create(
    interpreter: Interpreter,
    interpreterEc: ExecutionContext,
    kernelThreads: KernelThreads,
    logCtx: LoggerContext,
    extraHandler: MessageHandler,
    noExecuteInputFor: Set[String]
  ): IO[Kernel] =
    create(
      new InterpreterToIOInterpreter(interpreter, interpreterEc, logCtx),
      kernelThreads,
      logCtx,
      extraHandler,
      noExecuteInputFor
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
      MessageHandler.empty,
      Set.empty
    )

  def create(
    interpreter: IOInterpreter,
    kernelThreads: KernelThreads,
    logCtx: LoggerContext,
    extraHandler: MessageHandler,
    noExecuteInputFor: Set[String]
  ): IO[Kernel] =
    for {
      backgroundMessagesQueue <- Queue.unbounded[IO, (Channel, RawMessage)]
      executeQueue <- Queue.unbounded[IO, Option[(
        Option[(Channel, RawMessage)],
        Stream[IO, (Channel, RawMessage)]
      )]]
      otherQueue <- Queue.unbounded[IO, Option[Stream[IO, (Channel, RawMessage)]]]
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
      executeQueue,
      otherQueue,
      backgroundCommHandlerOpt,
      inputHandler,
      kernelThreads,
      logCtx,
      extraHandler,
      noExecuteInputFor
    )

}
