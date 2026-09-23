package almond.display

import java.net.URL

final class Markdown private (
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): Markdown =
    new Markdown(contentOrUrl, displayId)

  def withContent(code: String): Markdown =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Markdown =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Markdown =
    copy(contentOrUrl = Left(url))

  def data(): Map[String, String] =
    Map(Markdown.mimeType -> finalContent)

}

object Markdown extends TextDisplay.Builder[Markdown] {
  protected def build(contentOrUrl: Either[URL, String]): Markdown =
    new Markdown(contentOrUrl, UpdatableDisplay.generateId())

  def mimeType = "text/markdown"
}
