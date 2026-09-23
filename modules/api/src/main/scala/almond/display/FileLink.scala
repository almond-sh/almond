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
    val link0 = new URI(urlPrefix + link).toASCIIString
    beforeHtml +
      s"""<a href="$link0" target='_blank'>${FileLink.escapeHTML(link)}</a>""" +
      afterHtml
  }

  def data(): Map[String, String] =
    Map(Html.mimeType -> html)

}

object FileLink {
  def apply(link: String): FileLink =
    new FileLink(link, "", "<br>", "", UpdatableDisplay.generateId())

  // https://stackoverflow.com/a/25228492/3714539
  private def escapeHTML(s: String): String = {
    val out = new StringBuilder(java.lang.Math.max(16, s.length))
    for (i <- 0 until s.length) {
      val c = s.charAt(i)
      if (c > 127 || c == '"' || c == '<' || c == '>' || c == '&') {
        out.append("&#")
        out.append(c.toInt)
        out.append(';')
      }
      else
        out.append(c)
    }
    out.toString
  }
}
