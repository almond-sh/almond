package almond.display

import java.net.URL

final class Svg private (
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): Svg =
    new Svg(contentOrUrl, displayId)

  def withContent(code: String): Svg =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Svg =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Svg =
    copy(contentOrUrl = Left(url))

  def data(): Map[String, String] =
    Map(Svg.mimeType -> finalContent)

}

object Svg extends TextDisplay.Builder[Svg] {
  protected def build(contentOrUrl: Either[URL, String]): Svg =
    new Svg(contentOrUrl, UpdatableDisplay.generateId())

  def mimeType = "image/svg+xml"
}
