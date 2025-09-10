package almond.api

import almond.interpreter.api.{CommHandler, DisplayData, ExecuteResult, OutputHandler}
import jupyter.{Displayer, Displayers}

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Locale, UUID}

import scala.reflect.{ClassTag, classTag}

abstract class JupyterApi { api =>

  import JupyterApi.{ExceptionHandler, ExecuteHook, ExecuteRequest}

  /** Request input from the the Jupyter UI */
  final def stdin(prompt: String = "", password: Boolean = false): String =
    stdinOpt(prompt, password).getOrElse {
      throw new Exception("stdin not available")
    }

  def stdinOpt(prompt: String = "", password: Boolean = false): Option[String]

  def silent(s: Boolean): Unit = {}
  def silent: Boolean          = false

  protected implicit def changingPublish: OutputHandler =
    new almond.interpreter.api.OutputHandler.OnlyUpdateVia(commHandlerOpt)

  /** Jupyter publishing helper
    *
    * Allows to push display items to the front-end.
    */
  final implicit lazy val publish: OutputHandler =
    new OutputHandler.StableOutputHandler(changingPublish)

  def commHandlerOpt: Option[CommHandler] =
    None
  final implicit def commHandler: CommHandler =
    commHandlerOpt.getOrElse {
      throw new Exception("Comm handler not available (not supported)")
    }
  final def comm: CommHandler = commHandler

  protected def updatableResults0: JupyterApi.UpdatableResults

  final lazy val updatableResults: JupyterApi.UpdatableResults =
    updatableResults0

  def register[T: ClassTag](f: T => Map[String, String]): Unit =
    Displayers.register(
      classTag[T].runtimeClass.asInstanceOf[Class[T]],
      new Displayer[T] {
        import scala.collection.JavaConverters._
        def display(t: T) = f(t).asJava
      }
    )

  /** Add a hook that can preprocess cell code right before it's executed
    *
    * @param hook
    *   the hook to add
    * @return
    *   true if the hook was freshly added, false it was already added before this call
    */
  def addExecuteHook(hook: ExecuteHook): Boolean

  /** Remove a hook that can preprocess cell code right before it's executed
    *
    * @param hook
    *   the hook to remove
    * @return
    *   true if the hook was removed, false it wasn't found in the current hook list
    */
  def removeExecuteHook(hook: ExecuteHook): Boolean

  /** Add a hook that runs after each cell, and can alter the cell's result
    *
    * @param hook
    *   the hook to add
    * @return
    *   true if the hook was freshly added, false it was already added before this call
    */
  def addPostRunHook(hook: JupyterApi.PostRunHook): Boolean

  /** Remove a hook that runs after each cell, and can alter the cell's result
    *
    * @param hook
    *   the hook to remove
    * @return
    *   true if the hook was removed, false it wasn't found in the current hook list
    */
  def removePostRunHook(hook: JupyterApi.PostRunHook): Boolean

  /** List of hooks to run after each cell that can alter the cell's result */
  def postRunHooks(): Seq[JupyterApi.PostRunHook]

  /** Add a hook to be run right after a cell is interrupted
    *
    * @param name
    *   name to identify the hook (see `removePostInterruptHook`)
    * @param hook
    *   the hook to add
    * @return
    *   true if the hook was freshly added, false it was already added before this call
    */
  def addPostInterruptHook(name: String, hook: Any => Any): Boolean

  /** Remove a hook to be run right after a cell is interrupted
    *
    * @param name
    *   name that identifies the hook (see `addPostInterruptHook`)
    * @return
    *   true if the hook was removed, false it wasn't found in the current hook list
    */
  def removePostInterruptHook(name: String): Boolean

  /** List of hooks to be run right after a cell is interrupted */
  def postInterruptHooks(): Seq[(String, Any => Any)]

  /** Runs hooks meant to be run right after a cell is interrupted */
  def runPostInterruptHooks(): Unit

  /** Registers an exception handler, that can process exceptions thrown by user code
    *
    * @param handler
    *   the `ExceptionHandler` to register
    * @return
    *   true if the handler was freshly registered, false it was already registered before this call
    */
  def addExceptionHandler(handler: ExceptionHandler): Boolean

  /** Unregisters an exception handler
    *
    * @param handler
    *   the `ExceptionHandler` to unregister
    * @return
    *   true if the handler was unregistered, false it wasn't found in the current handler list
    */
  def removeExceptionHandler(handler: ExceptionHandler): Boolean

  /** List of exception handlers that should process exceptions thrown in user code */
  def exceptionHandlers(): Seq[ExceptionHandler]

  /** Helper to add simple exception handlers
    *
    * Match on the exception class or classes you'd like to handle, like
    * {{{
    *   kernel.handleExceptions {
    *     case ex: MyException => // ...
    *     case other: OtherException if other.thing == 2 => // ...
    *   }
    * }}}
    *
    * These handlers don't modify the exception class. The original exception will still be reported
    * to users.
    *
    * @param pf
    *   partial function defined for exceptions you'd like to handle
    */
  final def handleExceptions(pf: PartialFunction[Throwable, Unit]): Unit =
    addExceptionHandler { ex =>
      if (pf.isDefinedAt(ex))
        pf(ex)
      Some(ex)
    }

  def consoleOut: PrintStream
  def consoleErr: PrintStream

  /** Details about the current execute request, if any */
  def currentExecuteRequest(): Option[ExecuteRequest]
}

object JupyterApi {

  /** A hook, that can pre-process code right before it's executed
    */
  @FunctionalInterface
  abstract class ExecuteHook {

    /** Pre-processes code right before it's executed.
      *
      * Like when actual code is executed, `println` / `{Console,System}.{out,err}` get sent to the
      * cell output, stdin can be requested from users, CommHandler and OutputHandler can be used,
      * etc.
      *
      * When several hooks were added, they are called in the order they were added. The output of
      * the previous hook gets passed to the next one, as long as hooks return code to be executed
      * rather than an `ExecuteHookResult`.
      *
      * @param code
      *   Code to be pre-processed
      * @return
      *   Either code to be executed (right), or an `ExecuteHookResult` (left)
      */
    def hook(code: String): Either[ExecuteHookResult, String]
  }

  /** A hook, that can be run after each cell and can alter the cell's result */
  @FunctionalInterface
  abstract class PostRunHook {

    /** Processes a cell's result
      *
      * @param result
      *   the input cell result
      * @return
      *   the cell result, can be a new one or the input one as is
      */
    def process(result: ExecuteResult): ExecuteResult
  }

  /** Can be returned by `ExecuteHook.hook` to stop code execution.
    */
  sealed abstract class ExecuteHookResult extends Product with Serializable
  object ExecuteHookResult {

    /** Returns data to be displayed */
    final case class Success(data: DisplayData = DisplayData.empty) extends ExecuteHookResult

    /** Exception-like error
      *
      * If you'd like to build one out of an actual `Throwable`, just throw it. It will then be
      * caught while the hook is running, and sent to users.
      */
    final case class Error(
      name: String,
      message: String,
      stackTrace: List[String]
    ) extends ExecuteHookResult

    /** Tells the front-end that execution was aborted */
    case object Abort extends ExecuteHookResult

    /** Should instruct the front-end to prompt the user for exit */
    case object Exit extends ExecuteHookResult
  }

  private lazy val useRandomIds: Boolean =
    Option(System.getenv("ALMOND_USE_RANDOM_IDS"))
      .orElse(sys.props.get("almond.ids.random"))
      .forall(s => s == "1" || s.toLowerCase(Locale.ROOT) == "true")

  private val updatableIdCounter = new AtomicInteger(1111111)

  abstract class UpdatableResults {

    @deprecated("Use updatable instead", "0.4.1")
    def addVariable(k: String, v: String): Unit =
      updatable(k, v)
    @deprecated("Use update instead", "0.4.1")
    def updateVariable(k: String, v: String, last: Boolean): Unit =
      update(k, v, last)

    def updatable(k: String, v: String): Unit = {
      // temporary dummy implementation for binary compatibility
    }
    def updatable(v: String): String = {
      val id =
        if (useRandomIds)
          UUID.randomUUID().toString
        else
          updatableIdCounter.incrementAndGet().toString
      updatable(id, v)
      id
    }
    def update(k: String, v: String, last: Boolean): Unit = {
      // temporary dummy implementation for binary compatibility
    }
  }

  /** A handler that's given exceptions thrown by user code, and can change it or discard it */
  @FunctionalInterface
  abstract class ExceptionHandler {

    /** Handles the passed exception
      *
      * @param exception
      *   exception to handle
      * @return
      *   `None` if the exception should be discarded, or the passed exception or a new one wrapped
      *   in `Some(...)`
      */
    def handle(exception: Throwable): Option[Throwable]
  }

  object ExceptionHandler {

    /** Exception reported to users when an `ExceptionHandler` returned `None` */
    class NoException extends Exception
    def noException(): Throwable = {
      val ex = new NoException
      ex.setStackTrace(Array.empty)
      ex
    }
  }

  /** Details associated to an execute request */
  trait ExecuteRequest {

    /** Metadata of the request */
    def metadata: String

    /** Header of the request */
    def header: MessageHeader

    /** Parent header of the request, if any */
    def parentHeader: Option[MessageHeader]
  }

  /** Details about a Jupyter message header */
  trait MessageHeader {

    /** Unique ID for the message */
    def msgId: String

    /** Message username */
    def userName: String

    /** Unique ID for the session */
    def session: String

    /** Message type */
    def msgType: String

    /** Message protocol version */
    def version: Option[String]

    /** All message header object key-values */
    def entries: Map[String, String]
  }

}
