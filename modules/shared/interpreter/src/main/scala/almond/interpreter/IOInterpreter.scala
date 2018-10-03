package almond.interpreter

import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.interpreter.comm.CommManager
import almond.interpreter.input.InputManager
import almond.protocol.KernelInfo
import cats.effect.IO
import fs2.async.mutable.Signal

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

  // CommManager: manages the server-side comms (~objects that await messages)
  // CommHandler: allows to send messages to the client-side comms, and to register server comms to receive messages
  // FIXME Wrap the result of those in IO too?
  def commManagerOpt: Option[CommManager] = None
  def supportComm: Boolean = false
  def setCommHandler(commHandler: CommHandler): Unit = {}

  def isComplete(code: String): IO[Option[IsCompleteResult]] =
    IO.pure(None)

  def complete(code: String, pos: Int): IO[Completion] =
    IO.pure(Completion(pos, pos, Nil))

  def inspect(code: String, pos: Int, detailLevel: Int): IO[Option[Inspection]] =
    IO.pure(None)

  def executionCount: IO[Int]

  def kernelInfo: IO[KernelInfo]

  def cancelledSignal: Signal[IO, Boolean]

}
