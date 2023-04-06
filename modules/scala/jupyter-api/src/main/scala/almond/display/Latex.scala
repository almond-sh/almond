package almond.display

import java.net.URL

final class Latex private (
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): Latex =
    new Latex(contentOrUrl, displayId)

  def withContent(code: String): Latex =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Latex =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Latex =
    copy(contentOrUrl = Left(url))

  def data(): Map[String, String] =
    Map(Latex.mimeType -> finalContent)

}

object Latex extends TextDisplay.Builder[Latex] {
  protected def build(contentOrUrl: Either[URL, String]): Latex =
    new Latex(contentOrUrl, UpdatableDisplay.generateId())

  def mimeType = "text/latex"
}
