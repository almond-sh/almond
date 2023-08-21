package almond.api

import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import jupyter.{Displayer, Displayers}

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Locale, UUID}

import scala.reflect.{ClassTag, classTag}

abstract class JupyterApi { api =>

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

  def addExecuteHook(hook: JupyterApi.ExecuteHook): Boolean
  def removeExecuteHook(hook: JupyterApi.ExecuteHook): Boolean

  def addPostInterruptHook(name: String, hook: Any => Any): Boolean
  def removePostInterruptHook(name: String): Boolean
  def postInterruptHooks(): Seq[(String, Any => Any)]
  def runPostInterruptHooks(): Unit

  def consoleOut: PrintStream
  def consoleErr: PrintStream
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

}
