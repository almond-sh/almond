package almond.interpreter

import almond.interpreter.api.{CommHandler, OutputHandler}
import almond.interpreter.input.InputManager
import almond.interpreter.util.CancellableFuture
import almond.protocol.KernelInfo

trait Interpreter {

  /** Performs any heavyweight initialization.
    */
  def init(): Unit =
    ()

  /** [[KernelInfo]] of this kernel.
    *
    * Sent to frontends via a `kernel_info_reply` each time a `kernel_info_request` is received.
    */
  def kernelInfo(): KernelInfo

  /** Executes some code.
    *
    * @param code:
    *   code to run
    * @param storeHistory:
    *   whether the line count should be increased after this code is run
    * @param inputManager:
    *   optional [[InputManager]] to request input from the frontend
    * @param outputHandler:
    *   optional [[OutputHandler]] to send output to the frontend while the code is running (note
    *   that the final results should returned via the [[ExecuteResult]] rather than passed to this
    *   [[OutputHandler]])
    * @return
    *   resulting [[ExecuteResult]]
    */
  def execute(
    code: String,
    storeHistory: Boolean = true,
    inputManager: Option[InputManager] = None,
    outputHandler: Option[OutputHandler] = None
  ): ExecuteResult

  /** Current line count.
    *
    * Should be increased each time [[execute]] is called with `store_history` set to `true`.
    */
  def currentLine(): Int

  /** Whether this kernel can be interrupted via a call to [[interrupt]].
    */
  def interruptSupported: Boolean =
    false

  /** Interrupts the kernel, likely when [[execute]] or [[complete]] are running.
    */
  def interrupt(): Unit =
    ()

  /** Called in response to a shutdown message, before a reply has been sent.
    */
  def shutdown(): Unit =
    ()

  /** Whether the passed code is complete.
    *
    * Mostly used by `jupyter console`, to know whether the code entered should be evaluated or a
    * new prompt should be displayed for the entered code to be completed.
    */
  def isComplete(code: String): Option[IsCompleteResult] =
    None

  /** Asynchronously try to check whether some code is complete.
    *
    * This is normally called before [[isComplete()]]. If this returns a non-empty option, it is
    * assumed asynchronous completion checks are supported. Else, [[isComplete()]] is called.
    *
    * @param code:
    *   code to check for completion
    */
  def asyncIsComplete(code: String): Option[CancellableFuture[Option[IsCompleteResult]]] =
    None

  // warning: in the 2 methods below, pos should correspond to a code point index
  // (https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#cursor-pos-and-unicode-offsets)

  /** Tries to complete code.
    *
    * @param code:
    *   code to complete
    * @param pos:
    *   cursor position (as a unicode code point index) in code
    */
  def complete(code: String, pos: Int): Completion =
    Completion.empty(pos)

  /** Asynchronously try to complete code.
    *
    * This is normally called before [[complete()]]. If this returns a non-empty option, it is
    * assumed asynchronous completions are supported. Else, [[complete()]] is called.
    *
    * @param code:
    *   code to complete
    * @param pos:
    *   cursor position (as a unicode code point index) in code
    */
  def asyncComplete(code: String, pos: Int): Option[CancellableFuture[Completion]] =
    None

  /** Tries to complete code.
    *
    * @param code:
    *   code to complete
    */
  final def complete(code: String): Completion =
    complete(code, code.length)

  /** @param code:
    *   code to inspect
    * @param pos:
    *   cursor position (as a unicode code point index) in code
    * @param detailLevel
    * @return
    */
  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    None

  /** Asynchronously try to inspect code.
    *
    * This is normally called before [[inspect()]]. If this returns a non-empty option, it is
    * assumed asynchronous inspections are supported. Else, [[inspect()]] is called.
    *
    * @param code:
    *   code to inspect
    * @param pos:
    *   cursor position (as a unicode code point index)
    * @param detailLevel
    */
  def asyncInspect(
    code: String,
    pos: Int,
    detailLevel: Int
  ): Option[CancellableFuture[Option[Inspection]]] =
    None

  /** @param code:
    *   code to inspect
    * @param pos:
    *   cursor position (as a unicode code point index) in code
    * @return
    */
  final def inspect(code: String, pos: Int): Option[Inspection] =
    inspect(code, pos, detailLevel = 0)

  /** Whether this kernel handles custom messages (see [[CommHandler]]).
    */
  def supportComm: Boolean = false

  /** Provides a [[CommHandler]] that this kernel can use to send custom messages to the frontend.
    *
    * Called prior to any call to [[execute]], if and only if [[supportComm]] is true.
    *
    * See [[CommHandler]] for more details about custom messages.
    */
  def setCommHandler(commHandler: CommHandler): Unit =
    ()
}
