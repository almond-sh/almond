package almond.display

import java.net.URL

final class Text private (
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): Text =
    new Text(contentOrUrl, displayId)

  def withContent(code: String): Text =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Text =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Text =
    copy(contentOrUrl = Left(url))

  def data(): Map[String, String] =
    Map(Text.mimeType -> finalContent)

}

object Text extends TextDisplay.Builder[Text] {
  protected def build(contentOrUrl: Either[URL, String]): Text =
    new Text(contentOrUrl, UpdatableDisplay.generateId())

  def mimeType = "text/plain"
}
