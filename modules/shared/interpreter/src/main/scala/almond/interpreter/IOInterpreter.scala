package almond.interpreter

import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.interpreter.input.InputManager
import almond.protocol.KernelInfo
import cats.effect.IO
import fs2.concurrent.SignallingRef

trait IOInterpreter {

  def init: IO[Unit] =
    IO.unit

  def execute(
    code: String,
    storeHistory: Boolean,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler]
  ): IO[ExecuteResult]

  def interruptSupported: Boolean =
    false
  def interrupt: IO[Unit] =
    IO.unit

  def shutdown: IO[Unit]

  def supportComm: Boolean                           = false
  def setCommHandler(commHandler: CommHandler): Unit = {}

  def isComplete(code: String): IO[Option[IsCompleteResult]] =
    IO.pure(None)

  def complete(code: String, pos: Int): IO[Completion] =
    IO.pure(Completion.empty(pos))

  final def complete(code: String): IO[Completion] =
    complete(code, code.length)

  def inspect(code: String, pos: Int, detailLevel: Int): IO[Option[Inspection]] =
    IO.pure(None)

  def executionCount: IO[Int]

  def kernelInfo: IO[KernelInfo]

  def cancelledSignal: SignallingRef[IO, Boolean]

}
