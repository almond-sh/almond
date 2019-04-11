package almond

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import almond.api.{FullJupyterApi, JupyterApi}
import almond.internals.HtmlAnsiOutputStream
import almond.interpreter.api.CommHandler
import pprint.{TPrint, TPrintColors}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

/** Actual [[almond.api.JupyterApi]] instance */
final class JupyterApiImpl(
  execute: Execute,
  commHandlerOpt: => Option[CommHandler],
  replApi: ReplApiImpl
) extends FullJupyterApi {

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
    replApi.printSpecial(value, ident, custom, onChange, onChangeOrError, replApi.pprinter, Some(updatableResults))(tprint, tcolors, classTagT).getOrElse {
      replApi.Internal.print(value, ident, custom)(tprint, tcolors, classTagT)
    }

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
  override def commHandler =
    commHandlerOpt.getOrElse(super.commHandler)

  protected def updatableResults0: JupyterApi.UpdatableResults =
    execute.updatableResults
}

