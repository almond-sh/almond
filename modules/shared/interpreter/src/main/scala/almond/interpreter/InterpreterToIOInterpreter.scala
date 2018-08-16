package almond.interpreter

import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.interpreter.comm.CommManager
import almond.interpreter.input.InputManager
import almond.protocol.KernelInfo
import almond.util.OptionalLogger
import cats.effect.IO
import cats.syntax.apply._
import fs2.async
import fs2.async.mutable.Signal

import scala.concurrent.ExecutionContext

/**
  *
  * @param interpreterEc: [[ExecutionContext]] to compile and run user code on - should be single-threaded
  */
final class InterpreterToIOInterpreter(
  interpreter: Interpreter,
  interpreterEc: ExecutionContext
) extends IOInterpreter {

  private val log = OptionalLogger(getClass)

  override def commManagerOpt: Option[CommManager] =
    interpreter.commManagerOpt

  override def supportComm: Boolean = true

  override def setCommHandler(commHandler0: CommHandler): Unit =
    interpreter.setCommHandler(commHandler0)

  private val cancelledSignal0 = {
    implicit val ec = interpreterEc // maybe not the right ec, but that one shouldn't be used yet at that point
    async.signalOf[IO, Boolean](false).unsafeRunSync()
  }
  def cancelledSignal: Signal[IO, Boolean] =
    cancelledSignal0

  private def cancellable[T](io: Boolean => IO[T]): IO[T] =
    for {
      cancelled <- cancelledSignal.get
      _ <- IO.shift(interpreterEc)
      res <- io(cancelled)
    } yield res

  override def init: IO[Unit] =
    IO.shift(interpreterEc) *>
      IO(interpreter.init())

  def execute(
    line: String,
    outputHandler: Option[OutputHandler],
    inputManager: Option[InputManager],
    storeHistory: Boolean,
    currentMessageOpt: Option[Message[_]]
  ): IO[ExecuteResult] =
    cancellable {
      case true =>
        IO.pure(ExecuteResult.Abort)
      case false =>
        IO {
          log.info(s"Executing $line")
          val res =
            try interpreter.execute(line, outputHandler, inputManager, storeHistory, currentMessageOpt)
            catch {
              case t: Throwable =>
                log.error(s"Error when executing $line", t)
                throw t
            }
          log.info(s"Result: $res")
          res
        }
    }

  override def interruptSupported: Boolean =
    interpreter.interruptSupported
  override val interrupt: IO[Unit] =
    IO(interpreter.interrupt())

  val executionCount: IO[Int] =
    IO(interpreter.currentLine())

  val kernelInfo: IO[KernelInfo] =
    IO(interpreter.kernelInfo())

  override def isComplete(code: String): IO[Option[IsCompleteResult]] =
    cancellable {
      case true =>
        IO.pure(None)
      case false =>
        IO(interpreter.isComplete(code))
    }

  override def complete(code: String, pos: Int): IO[Completion] =
    cancellable {
      case true =>
        IO.pure(Completion(pos, pos, Nil))
      case false =>
        IO(interpreter.complete(code, pos))
    }

  override def inspect(code: String, pos: Int, detailLevel: Int): IO[Option[Inspection]] =
    cancellable {
      case true =>
        IO.pure(None)
      case false =>
        IO(interpreter.inspect(code, pos, detailLevel))
    }

}
