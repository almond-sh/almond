package almond.interpreter

import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.interpreter.comm.CommManager
import almond.interpreter.input.InputManager
import almond.protocol.KernelInfo

import scala.concurrent.ExecutionContext

trait Interpreter {

  def init(): Unit =
    ()

  def kernelInfo(): KernelInfo

  def execute(
    line: String,
    storeHistory: Boolean = true,
    inputManager: Option[InputManager] = None,
    outputHandler: Option[OutputHandler] = None
  ): ExecuteResult

  def currentLine(): Int

  def interruptSupported: Boolean =
    false
  // Should be called while execute or complete are running, but still a chance this gets called at other times
  def interrupt(): Unit =
    ()


  // warning: in the 3 methods below, pos should correspond to a code point index
  // (https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#cursor-pos-and-unicode-offsets)
  def isComplete(code: String): Option[IsCompleteResult] =
    None
  def complete(code: String, pos: Int): Completion =
    Completion(pos, pos, Nil)
  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    None

  def commManagerOpt: Option[CommManager] =
    None
  def setCommHandler(commHandler: CommHandler): Unit =
    ()

  def ioInterpreter(interpreterEc: ExecutionContext): IOInterpreter =
    new InterpreterToIOInterpreter(this, interpreterEc)
}
