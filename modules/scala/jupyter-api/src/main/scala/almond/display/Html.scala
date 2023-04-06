package almond.display

import java.net.URL

final class Html private (
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): Html =
    new Html(contentOrUrl, displayId)

  def withContent(code: String): Html =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Html =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Html =
    copy(contentOrUrl = Left(url))

  def data(): Map[String, String] =
    Map(Html.mimeType -> finalContent)

}

object Html extends TextDisplay.Builder[Html] {
  protected def build(contentOrUrl: Either[URL, String]): Html =
    new Html(contentOrUrl, UpdatableDisplay.generateId())

  def mimeType: String = "text/html"
}
