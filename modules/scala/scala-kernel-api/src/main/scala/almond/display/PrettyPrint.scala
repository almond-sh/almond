package almond.display

import almond.api.FullJupyterApi
import ammonite.repl.api.ReplAPI
import ammonite.util.Ref
import pprint.PPrinter

final class PrettyPrint private (
  val value: Any,
  val pprinter: Ref[PPrinter],
  val fadeIn: Option[Boolean],
  val ansiToHtml: String => String,
  val displayId: String
) extends UpdatableDisplay {

  private def copy(
    value: Any = value,
    fadeIn: Option[Boolean] = fadeIn
  ): PrettyPrint =
    new PrettyPrint(value, pprinter, fadeIn, ansiToHtml, displayId)

  private def nextFadeInt =
    if (fadeIn.isEmpty) Some(true) else fadeIn

  def withValue(value: Any): PrettyPrint =
    copy(value = value, fadeIn = nextFadeInt)
  def withFadeIn(): PrettyPrint =
    copy(fadeIn = Some(true))
  def withFadeIn(fadeIn: Boolean): PrettyPrint =
    copy(fadeIn = Some(fadeIn))
  def withFadeIn(fadeInOpt: Option[Boolean]): PrettyPrint =
    copy(fadeIn = fadeInOpt)

  private def text: String =
    pprinter().tokenize(
      value,
      height = pprinter().defaultHeight
    ).map(_.render).mkString

  private def fadeIn0 = fadeIn.exists(identity)

  private def prefix = {
    val extra =
      if (fadeIn0)
        """<style>@keyframes fadein { from { opacity: 0; } to { opacity: 1; } }</style><div style="animation: fadein 2s;">"""
      else
        ""
    // pre and div are for jupyterlab, code is for nteract
    extra + """<div class="jp-RenderedText"><pre><code>"""
  }
  private def suffix = {
    val extra =
      if (fadeIn0) "</div>"
      else ""
    "</code></pre></div>" + extra
  }

  private def html: String =
    prefix + ansiToHtml(text) + suffix

  def data(): Map[String, String] =
    Map(
      Text.mimeType -> text,
      Html.mimeType -> html
    )

}

object PrettyPrint {
  def apply(value: Any)(implicit jupyterApi: FullJupyterApi, replApi: ReplAPI): PrettyPrint =
    new PrettyPrint(
      value,
      replApi.pprinter,
      None,
      jupyterApi.Internal.ansiTextToHtml _,
      UpdatableDisplay.generateId()
    )

  def apply(value: Any, pprinter: Ref[PPrinter], ansiTextToHtml: String => String): PrettyPrint =
    new PrettyPrint(value, pprinter, None, ansiTextToHtml, UpdatableDisplay.generateId())
}
