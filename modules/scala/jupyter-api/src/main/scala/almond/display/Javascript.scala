package almond.display

import java.net.URL

final class Javascript private (
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): Javascript =
    new Javascript(contentOrUrl, displayId)

  def withContent(code: String): Javascript =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Javascript =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Javascript =
    copy(contentOrUrl = Left(url))

  def data(): Map[String, String] =
    Map(Javascript.mimeType -> finalContent)

}

object Javascript extends TextDisplay.Builder[Javascript] {
  protected def build(contentOrUrl: Either[URL, String]): Javascript =
    new Javascript(contentOrUrl, UpdatableDisplay.generateId())

  def mimeType = "application/javascript"
}
