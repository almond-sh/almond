package almond.display

import java.net.URI

final class FileLink private (
  val link: String,
  val beforeHtml: String,
  val afterHtml: String,
  val urlPrefix: String,
  val displayId: String
) extends UpdatableDisplay {

  private def copy(
    link: String = link,
    beforeHtml: String = beforeHtml,
    afterHtml: String = afterHtml,
    urlPrefix: String = urlPrefix
  ): FileLink =
    new FileLink(link, beforeHtml, afterHtml, urlPrefix, displayId)

  def withLink(link: String): FileLink =
    copy(link = link)
  def withBeforeHtml(beforeHtml: String): FileLink =
    copy(beforeHtml = beforeHtml)
  def withAfterHtml(afterHtml: String): FileLink =
    copy(afterHtml = afterHtml)
  def withUrlPrefix(urlPrefix: String): FileLink =
    copy(urlPrefix = urlPrefix)

  private def html: String = {
    HtmlUtils.checkUrl(urlPrefix + link)
    val link0 = new URI(urlPrefix + link).toASCIIString
    beforeHtml +
      s"""<a href="${HtmlUtils.escape(link0)}" target='_blank'>${HtmlUtils.escape(link)}</a>""" +
      afterHtml
  }

  def data(): Map[String, String] =
    Map(Html.mimeType -> html)

}

object FileLink {
  def apply(link: String): FileLink =
    new FileLink(link, "", "<br>", "", UpdatableDisplay.generateId())
}
