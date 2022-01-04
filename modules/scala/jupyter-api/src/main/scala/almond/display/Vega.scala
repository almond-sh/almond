package almond.display

import java.net.URL

final class Vega private(
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): Vega =
    new Vega(contentOrUrl, displayId)

  def withContent(code: String): Vega =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Vega =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Vega =
    copy(contentOrUrl = Left(url))

  def data(): Map[String, String] =
    Map(Vega.mimeType -> finalContent)

}

object Vega extends TextDisplay.Builder[Vega] {
  protected def build(contentOrUrl: Either[URL, String]): Vega =
    new Vega(contentOrUrl, UpdatableDisplay.generateId())

  def mimeType: String = "application/vnd.vega.v5+json"
}
