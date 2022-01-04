package almond.display

import java.net.URL

final class VegaLite private(
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): VegaLite =
    new VegaLite(contentOrUrl, displayId)

  def withContent(code: String): VegaLite =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): VegaLite =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): VegaLite =
    copy(contentOrUrl = Left(url))

  def data(): Map[String, String] =
    Map(Vega.mimeType -> finalContent)

}

object VegaLite extends TextDisplay.Builder[VegaLite] {
  protected def build(contentOrUrl: Either[URL, String]): VegaLite =
    new VegaLite(contentOrUrl, UpdatableDisplay.generateId())

  def mimeType: String = "application/vnd.vegalite.v3+json"
}
