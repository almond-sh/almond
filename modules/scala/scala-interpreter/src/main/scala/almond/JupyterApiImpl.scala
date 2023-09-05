package almond

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

import almond.api.{FullJupyterApi, JupyterApi}
import almond.internals.HtmlAnsiOutputStream
import almond.interpreter.api.CommHandler
import almond.logger.LoggerContext
import ammonite.util.Ref
import pprint.{TPrint, TPrintColors}

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/** Actual [[almond.api.JupyterApi]] instance */
final class JupyterApiImpl(
  execute: Execute,
  commHandlerOpt0: => Option[CommHandler],
  replApi: ReplApiImpl,
  silent0: Ref[Boolean],
  protected val allowVariableInspector: Option[Boolean],
  val kernelClassLoader: ClassLoader,
  val consoleOut: PrintStream,
  val consoleErr: PrintStream,
  logCtx: LoggerContext
) extends FullJupyterApi with VariableInspectorApiImpl {

  private val log = logCtx(getClass)

  protected def variableInspectorImplPPrinter() = replApi.pprinter()

  protected def printOnChange[T](
    value: => T,
    ident: String,
    custom: Option[String],
    onChange: Option[(T => Unit) => Unit],
    onChangeOrError: Option[(Either[Throwable, T] => Unit) => Unit]
  )(implicit
    tprint: TPrint[T],
    tcolors: TPrintColors,
    classTagT: ClassTag[T]
  ): Iterator[String] =
    replApi.printSpecial(
      value,
      ident,
      custom,
      onChange,
      onChangeOrError,
      replApi.pprinter,
      Some(updatableResults)
    )(tprint, tcolors, classTagT).getOrElse {
      replApi.Internal.print(value, ident, custom)(tprint, tcolors, classTagT)
    }

  override def silent(s: Boolean): Unit = silent0.update(s)
  override def silent: Boolean          = silent0.apply()

  protected def ansiTextToHtml(text: String): String = {
    val baos = new ByteArrayOutputStream
    val haos = new HtmlAnsiOutputStream(baos)
    haos.write(text.getBytes(StandardCharsets.UTF_8))
    haos.close()
    baos.toString("UTF-8")
  }

  def stdinOpt(prompt: String, password: Boolean): Option[String] =
    for (m <- execute.currentInputManagerOpt)
      yield Await.result(m.readInput(prompt, password), Duration.Inf)

  override def changingPublish =
    execute.currentPublishOpt.getOrElse(super.changingPublish)
  override def commHandlerOpt =
    commHandlerOpt0

  protected def updatableResults0: JupyterApi.UpdatableResults =
    execute.updatableResults

  private val executeHooks0 = new mutable.ListBuffer[JupyterApi.ExecuteHook]
  def executeHooks: Seq[JupyterApi.ExecuteHook] =
    executeHooks0.toList
  def addExecuteHook(hook: JupyterApi.ExecuteHook): Boolean =
    !executeHooks0.contains(hook) && {
      executeHooks0.append(hook)
      true
    }
  def removeExecuteHook(hook: JupyterApi.ExecuteHook): Boolean = {
    val idx = executeHooks0.indexOf(hook)
    idx >= 0 && {
      executeHooks0.remove(idx)
      true
    }
  }

  private val postInterruptHooks0 = new mutable.ListBuffer[(String, Any => Any)]
  def addPostInterruptHook(name: String, hook: Any => Any): Boolean =
    !postInterruptHooks0.map(_._1).contains(name) && {
      postInterruptHooks0.append((name, hook))
      true
    }
  def removePostInterruptHook(name: String): Boolean = {
    val idx = postInterruptHooks0.map(_._1).indexOf(name)
    idx >= 0 && {
      postInterruptHooks0.remove(idx)
      true
    }
  }
  def postInterruptHooks(): Seq[(String, Any => Any)] = postInterruptHooks0.toList
  def runPostInterruptHooks(): Unit =
    try Function.chain(postInterruptHooks0.map(_._2)).apply(())
    catch {
      case NonFatal(e) =>
        log.warn("Caught exception while running post-interrupt hooks", e)
    }

}
