package almond

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

import almond.api.{FullJupyterApi, JupyterApi}
import almond.internals.HtmlAnsiOutputStream
import almond.interpreter.Message
import almond.interpreter.api.CommHandler
import almond.logger.LoggerContext
import almond.protocol.{Execute => ProtocolExecute, Header}
import ammonite.util.Ref
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
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
  logCtx: LoggerContext,
  currentExecuteRequest0: => Option[Message[ProtocolExecute.Request]]
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
      replApi.Internal.print(value, ident, custom)(using tprint, tcolors, classTagT)
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

  private val postRunHooks0 = new mutable.ListBuffer[JupyterApi.PostRunHook]
  def postRunHooks(): Seq[JupyterApi.PostRunHook] =
    postRunHooks0.toList
  def addPostRunHook(hook: JupyterApi.PostRunHook): Boolean =
    !postRunHooks0.contains(hook) && {
      postRunHooks0.append(hook)
      true
    }
  def removePostRunHook(hook: JupyterApi.PostRunHook): Boolean = {
    val idx = postRunHooks0.indexOf(hook)
    idx >= 0 && {
      postRunHooks0.remove(idx)
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

  private val exceptionHandlers0 = new mutable.ListBuffer[JupyterApi.ExceptionHandler]
  def addExceptionHandler(handler: JupyterApi.ExceptionHandler): Boolean =
    !exceptionHandlers0.contains(handler) && {
      exceptionHandlers0.append(handler)
      true
    }
  def removeExceptionHandler(handler: JupyterApi.ExceptionHandler): Boolean = {
    val idx = exceptionHandlers0.indexOf(handler)
    idx >= 0 && {
      exceptionHandlers0.remove(idx)
      true
    }
  }
  def exceptionHandlers(): Seq[JupyterApi.ExceptionHandler] =
    exceptionHandlers0.toList

  def currentExecuteRequest(): Option[JupyterApi.ExecuteRequest] =
    currentExecuteRequest0.map(JupyterApiImpl.executeRequest)
}

object JupyterApiImpl {
  private lazy val mapCodec: JsonValueCodec[Map[String, String]] =
    JsonCodecMaker.make
  private def messageHeader(header: Header): JupyterApi.MessageHeader =
    new JupyterApi.MessageHeader {
      def msgId: String           = header.msg_id
      def userName: String        = header.username
      def session: String         = header.session
      def msgType: String         = header.msg_type
      def version: Option[String] = header.version

      private lazy val headerMap: Map[String, String] =
        header.rawContentOpt match {
          case Some(rawHeader) =>
            readFromArray(rawHeader.value)(mapCodec)
          case None =>
            Map(
              "msg_id"   -> header.msg_id,
              "username" -> header.username,
              "session"  -> header.session,
              "msg_type" -> header.msg_type
            ) ++ header.version.map("version" -> _)
        }
      def entries: Map[String, String] = headerMap
    }
  private def executeRequest(msg: Message[ProtocolExecute.Request]): JupyterApi.ExecuteRequest =
    new JupyterApi.ExecuteRequest {
      def metadata: String =
        new String(msg.metadata.value, StandardCharsets.UTF_8)
      def header: JupyterApi.MessageHeader =
        messageHeader(msg.header)
      def parentHeader: Option[JupyterApi.MessageHeader] =
        msg.parent_header.map(messageHeader)
    }
}
