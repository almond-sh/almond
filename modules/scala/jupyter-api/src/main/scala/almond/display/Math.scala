package almond.display

import java.net.URL

final class Math private (
  val contentOrUrl: Either[URL, String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl
  ): Math =
    new Math(contentOrUrl, displayId)

  def withContent(code: String): Math =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Math =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Math =
    copy(contentOrUrl = Left(url))

  private def latex: String = {
    val finalContent0 = finalContent
      .dropRight(finalContent.reverseIterator.takeWhile(_ == '$').length)
      .dropWhile(_ == '$')
    "$\\displaystyle " + finalContent0 + "$"
  }

  def data(): Map[String, String] =
    Map(Latex.mimeType -> latex)

}

object Math extends TextDisplay.Builder[Math] {
  protected def build(contentOrUrl: Either[URL, String]): Math =
    new Math(contentOrUrl, UpdatableDisplay.generateId())
}
