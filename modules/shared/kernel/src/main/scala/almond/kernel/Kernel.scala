package almond.kernel

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import almond.channels.zeromq.{ZeromqRegistration, ZeromqThreads}
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
import almond.protocol.{Header, Protocol, Registration, Status, Connection => JsonConnection}
import cats.effect.IO
import cats.effect.std.Queue
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
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
        noExecuteInputFor,
        kernelThreads.ioRuntime
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
                msg_id = UUID.randomUUID().toString,
                username = "username",
                session =
                  UUID.randomUUID().toString, // Would there be a way to get the session id from the client?
                msg_type = Status.messageType.messageType,
                version = Some(Protocol.versionStr)
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
                      log.warn(
                        s"Ignoring unhandled message on $channel:${System.lineSeparator()}$rawMessage"
                      )
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

  /** @param connection
    * @param kernelId
    * @param zeromqThreads
    * @param leftoverMessages
    * @param lingerDuration
    * @param bindToRandomPorts
    *   if non-empty, bind to random ports for channels where no port is specified or the port is 0.
    *   Should contain the path to the connection file to overwrite.
    * @return
    */
  def runOnConnection(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    lingerDuration: Duration,
    bindToRandomPorts: Option[Path]
  ): IO[Unit] =
    for {
      t <- runOnConnectionAllowClose0(
        connection,
        kernelId,
        zeromqThreads,
        leftoverMessages,
        autoClose = true,
        lingerDuration = lingerDuration,
        bindToRandomPorts = bindToRandomPorts.nonEmpty,
        onBound = onBound(zeromqThreads, bindToRandomPorts, None)
      )
      (run, _) = t
      _ <- run
    } yield ()

  private def withPorts(
    params: ConnectionParameters,
    ports: Map[Option[Channel], Int]
  ): ConnectionParameters = {
    var p = params
    for (port <- ports.get(Some(Channel.Requests)))
      p = p.copy(shell_port = port)
    for (port <- ports.get(Some(Channel.Control)))
      p = p.copy(control_port = port)
    for (port <- ports.get(Some(Channel.Publish)))
      p = p.copy(iopub_port = port)
    for (port <- ports.get(Some(Channel.Input)))
      p = p.copy(stdin_port = port)
    for (port <- ports.get(None))
      p = p.copy(hb_port = port)
    p
  }

  private def overwriteConnectionFile(path: Path, params: ConnectionParameters): Unit = {
    val b = writeToArray(JsonConnection.fromParams(params))(JsonConnection.codec)
    Files.write(path, b)
  }

  /** Actions to run once the kernel channels are bound
    *
    * @param overwriteConnectionFileOpt
    *   connection file to overwrite, if random ports were picked
    * @param registrationOpt
    *   registration service to send the connection info to (kernel startup handshake)
    */
  private def onBound(
    zeromqThreads: ZeromqThreads,
    overwriteConnectionFileOpt: Option[Path],
    registrationOpt: Option[Registration]
  ): (ConnectionParameters, Boolean) => IO[Unit] = {
    (params, pickedRandomPorts) =>
      val overwrite = overwriteConnectionFileOpt match {
        case Some(connectionFile) if pickedRandomPorts =>
          IO(overwriteConnectionFile(connectionFile, params))
        case _ =>
          IO.unit
      }
      val register = registrationOpt match {
        case Some(registration) =>
          ZeromqRegistration.sendConnectionInfo(
            zeromqThreads.context,
            registration,
            params,
            logCtx
          )
        case None =>
          IO.unit
      }
      overwrite.flatMap(_ => register)
  }

  private def runOnConnectionAllowClose0(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Boolean,
    onBound: (ConnectionParameters, Boolean) => IO[Unit]
  ): IO[(IO[Unit], Connection)] =
    for {
      c <- connection.channels(
        bind = true,
        zeromqThreads,
        lingerPeriod = Some(5.minutes),
        logCtx = logCtx,
        identityOpt = Some(kernelId),
        bindToRandomPorts = bindToRandomPorts
      )
    } yield {
      val run0 =
        for {
          ports <- c.open
          _ <- {
            assert(bindToRandomPorts || ports.isEmpty)
            onBound(withPorts(connection, ports), ports.nonEmpty)
          }
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

  /** @param connection
    * @param kernelId
    * @param zeromqThreads
    * @param leftoverMessages
    * @param autoClose
    * @param lingerDuration
    * @param bindToRandomPorts
    *   if non-empty, bind to random ports for channels where no port is specified or the port is 0.
    *   Should contain the path to the connection file to overwrite.
    * @return
    */
  def runOnConnectionAllowClose(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Option[Path]
  ): IO[(IO[Seq[(Channel, RawMessage)]], Connection)] =
    runOnConnectionAllowClose(
      connection,
      kernelId,
      zeromqThreads,
      leftoverMessages,
      autoClose,
      lingerDuration,
      bindToRandomPorts,
      None
    )

  /** @param registrationOpt
    *   if non-empty, send the connection info (actual ports) to that registration service once the
    *   channels are bound (kernel startup handshake)
    */
  def runOnConnectionAllowClose(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Option[Path],
    registrationOpt: Option[Registration]
  ): IO[(IO[Seq[(Channel, RawMessage)]], Connection)] =
    runOnConnectionAllowClose1(
      connection,
      kernelId,
      zeromqThreads,
      leftoverMessages,
      autoClose,
      lingerDuration,
      bindToRandomPorts = bindToRandomPorts.nonEmpty,
      onBound = onBound(zeromqThreads, bindToRandomPorts, registrationOpt)
    )

  private def runOnConnectionAllowClose1(
    connection: ConnectionParameters,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Boolean,
    onBound: (ConnectionParameters, Boolean) => IO[Unit]
  ): IO[(IO[Seq[(Channel, RawMessage)]], Connection)] =
    runOnConnectionAllowClose0(
      connection,
      kernelId,
      zeromqThreads,
      leftoverMessages,
      autoClose,
      lingerDuration,
      bindToRandomPorts = bindToRandomPorts,
      onBound = onBound
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

  /** @param connectionPath
    * @param kernelId
    * @param zeromqThreads
    * @param leftoverMessages
    * @param autoClose
    * @param lingerDuration
    * @param bindToRandomPorts
    *   if non-empty, bind to random ports for channels where no port is specified or the port is 0.
    *   Should contain the path to the connection file to overwrite.
    * @return
    */
  def runOnConnectionFileAllowClose(
    connectionPath: Path,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Option[Path]
  ): IO[(IO[Seq[(Channel, RawMessage)]], Connection)] =
    runOnConnectionFileAllowClose(
      connectionPath,
      kernelId,
      zeromqThreads,
      leftoverMessages,
      autoClose,
      lingerDuration,
      bindToRandomPorts,
      None
    )

  /** @param connectionPath
    *   path to a connection file, or to a registration file (kernel startup handshake). In the
    *   latter case, channels are bound to random ports, that are then sent to the registration
    *   service, and `bindToRandomPorts` is ignored.
    * @param registrationOpt
    *   if non-empty, `connectionPath` must be a connection file, and the connection info (actual
    *   ports) is sent to that registration service once the channels are bound
    */
  def runOnConnectionFileAllowClose(
    connectionPath: Path,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Option[Path],
    registrationOpt: Option[Registration]
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
      registrationFromFileOpt <-
        if (registrationOpt.isEmpty) Registration.fromPathIfRegistration(connectionPath)
        else IO.pure(None)
      value <- registrationFromFileOpt match {
        case Some(registration) =>
          // Not overwriting the registration file, the actual ports are sent to the registration
          // service instead
          runOnConnectionAllowClose1(
            registration.connectionParameters,
            kernelId,
            zeromqThreads,
            leftoverMessages,
            autoClose,
            lingerDuration,
            bindToRandomPorts = true,
            onBound = onBound(zeromqThreads, None, Some(registration))
          )
        case None =>
          JsonConnection.fromPath(connectionPath).flatMap { connection =>
            runOnConnectionAllowClose(
              connection.connectionParameters,
              kernelId,
              zeromqThreads,
              leftoverMessages,
              autoClose,
              lingerDuration,
              bindToRandomPorts = bindToRandomPorts,
              registrationOpt = registrationOpt
            )
          }
      }
    } yield value

  /** @param connectionPath
    * @param kernelId
    * @param zeromqThreads
    * @param leftoverMessages
    * @param autoClose
    * @param lingerDuration
    * @param bindToRandomPorts
    *   if non-empty, bind to random ports for channels where no port is specified or the port is 0.
    *   Should contain the path to the connection file to overwrite.
    * @return
    */
  def runOnConnectionFile(
    connectionPath: Path,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Option[Path]
  ): IO[Unit] =
    for {
      t <- runOnConnectionFileAllowClose(
        connectionPath,
        kernelId,
        zeromqThreads,
        leftoverMessages,
        autoClose,
        lingerDuration,
        bindToRandomPorts = bindToRandomPorts
      )
      (run, _) = t
      _ <- run
    } yield ()

  /** @param connectionPath
    * @param kernelId
    * @param zeromqThreads
    * @param leftoverMessages
    * @param autoClose
    * @param lingerDuration
    * @param bindToRandomPorts
    *   if non-empty, bind to random ports for channels where no port is specified or the port is 0.
    *   Should contain the path to the connection file to overwrite.
    * @return
    */
  def runOnConnectionFile(
    connectionPath: String,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Option[Path]
  ): IO[Unit] =
    for {
      t <- runOnConnectionFileAllowClose(
        connectionPath,
        kernelId,
        zeromqThreads,
        leftoverMessages,
        autoClose,
        lingerDuration,
        bindToRandomPorts = bindToRandomPorts
      )
      (run, _) = t
      _ <- run
    } yield ()

  /** @param connectionPath
    * @param kernelId
    * @param zeromqThreads
    * @param leftoverMessages
    * @param autoClose
    * @param lingerDuration
    * @param bindToRandomPorts
    *   if non-empty, bind to random ports for channels where no port is specified or the port is 0.
    *   Should contain the path to the connection file to overwrite.
    * @return
    */
  def runOnConnectionFileAllowClose(
    connectionPath: String,
    kernelId: String,
    zeromqThreads: ZeromqThreads,
    leftoverMessages: Seq[(Channel, RawMessage)],
    autoClose: Boolean,
    lingerDuration: Duration,
    bindToRandomPorts: Option[Path]
  ): IO[(IO[Seq[(Channel, RawMessage)]], Connection)] =
    runOnConnectionFileAllowClose(
      Paths.get(connectionPath),
      kernelId,
      zeromqThreads,
      leftoverMessages,
      autoClose,
      lingerDuration,
      bindToRandomPorts = bindToRandomPorts
    )

}

object Kernel {

  def create(
    interpreter: Interpreter,
    interpreterEc: ExecutionContext,
    kernelThreads: KernelThreads,
    cancellableEc: ExecutionContext,
    logCtx: LoggerContext,
    extraHandler: MessageHandler,
    noExecuteInputFor: Set[String]
  ): IO[Kernel] =
    create(
      new InterpreterToIOInterpreter(
        interpreter,
        interpreterEc,
        logCtx,
        kernelThreads.ioRuntime,
        cancellableEc
      ),
      kernelThreads,
      logCtx,
      extraHandler,
      noExecuteInputFor
    )

  def create(
    interpreter: Interpreter,
    interpreterEc: ExecutionContext,
    kernelThreads: KernelThreads,
    cancellableEc: ExecutionContext,
    logCtx: LoggerContext = LoggerContext.nop
  ): IO[Kernel] =
    create(
      interpreter,
      interpreterEc,
      kernelThreads,
      cancellableEc,
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
            val h = new DefaultCommHandler(
              backgroundMessagesQueue,
              kernelThreads.commEc,
              kernelThreads.ioRuntime
            )
            interpreter.setCommHandler(h)
            h
          }
        else
          None
      }
      inputHandler <- IO {
        new InputHandler(kernelThreads.futureEc, logCtx, kernelThreads.ioRuntime)
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
