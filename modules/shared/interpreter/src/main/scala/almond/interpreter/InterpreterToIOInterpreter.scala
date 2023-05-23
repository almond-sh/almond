package almond.interpreter

import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.interpreter.input.InputManager
import almond.interpreter.util.Cancellable
import almond.logger.LoggerContext
import almond.protocol.KernelInfo
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.apply._
import fs2.concurrent.{Signal, SignallingRef}

import scala.concurrent.ExecutionContext

/** @param interpreterEc:
  *   [[ExecutionContext]] to compile and run user code on - should be single-threaded
  */
final class InterpreterToIOInterpreter(
  interpreter: Interpreter,
  interpreterEc: ExecutionContext,
  logCtx: LoggerContext
) extends IOInterpreter {

  private val log = logCtx(getClass)

  override def supportComm: Boolean = true

  override def setCommHandler(commHandler0: CommHandler): Unit =
    interpreter.setCommHandler(commHandler0)

  private val cancelledSignal0 =
    // maybe not the right IORuntime, but that one shouldn't be used yet at that point
    SignallingRef[IO, Boolean](false).unsafeRunSync()(IORuntime.global)
  def cancelledSignal: SignallingRef[IO, Boolean] =
    cancelledSignal0

  /** Check whether an [[IO]] should be cancelled because of stop-or-error prior to running it.
    *
    * The passed function is called immediately prior to evaluating the [[IO]] it returns. If
    * stop-on-error was triggered while this [[IO]] was queued for execution, the boolean given to
    * the passed function is true, and the returned [[IO]] can take it into account, typically
    * returning an empty result.
    */
  private def cancellable[T](io: Boolean => IO[T]): IO[T] =
    for {
      cancelled <- cancelledSignal.get
      res       <- io(cancelled).evalOn(interpreterEc)
    } yield res

  override def init: IO[Unit] =
    IO(interpreter.init()).evalOn(interpreterEc)

  def execute(
    line: String,
    storeHistory: Boolean,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler]
  ): IO[ExecuteResult] =
    cancellable {
      case true =>
        IO.pure(ExecuteResult.Abort)
      case false =>
        IO {
          log.debug(s"Executing $line")
          val res =
            try interpreter.execute(line, storeHistory, inputManager, outputHandler)
            catch {
              case t: Throwable =>
                log.error(s"Error when executing $line", t)
                throw t
            }
          log.debug(s"Result: $res")
          res
        }
    }

  override def interruptSupported: Boolean =
    interpreter.interruptSupported
  override val interrupt: IO[Unit] =
    IO(interpreter.interrupt())

  override val shutdown: IO[Unit] =
    IO(interpreter.shutdown())

  val executionCount: IO[Int] =
    IO(interpreter.currentLine())

  val kernelInfo: IO[KernelInfo] =
    IO(interpreter.kernelInfo())

  private val completionCheckCancellable = new Cancellable[String, Option[IsCompleteResult]](
    {
      code =>
        cancellable {
          case true =>
            IO.pure(None)
          case false =>
            IO(interpreter.isComplete(code))
        }
    },
    {
      code =>
        interpreter.asyncIsComplete(code)
    }
  )

  private val completionCancellable = new Cancellable[(String, Int), Completion](
    {
      case (code, pos) =>
        cancellable {
          case true =>
            IO.pure(Completion.empty(pos))
          case false =>
            IO(interpreter.complete(code, pos))
        }
    },
    {
      case (code, pos) =>
        interpreter.asyncComplete(code, pos)
    }
  )

  private val inspectionCancellable = new Cancellable[(String, Int, Int), Option[Inspection]](
    {
      case (code, pos, detailLevel) =>
        cancellable {
          case true =>
            IO.pure(None)
          case false =>
            IO(interpreter.inspect(code, pos, detailLevel))
        }
    },
    {
      case (code, pos, detailLevel) =>
        interpreter.asyncInspect(code, pos, detailLevel)
    }
  )

  override def isComplete(code: String): IO[Option[IsCompleteResult]] =
    completionCheckCancellable.run(code)

  override def complete(code: String, pos: Int): IO[Completion] =
    completionCancellable.run((code, pos))

  override def inspect(code: String, pos: Int, detailLevel: Int): IO[Option[Inspection]] =
    inspectionCancellable.run((code, pos, detailLevel))

}
