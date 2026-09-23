package almond.display

import java.net.URL

final class Json private (
  val contentOrUrl: Either[URL, String],
  // FIXME This may not be the right terminology (see https://en.wikipedia.org/wiki/Media_type).
  //       This could also be generalized to the other Display classes.
  val vendorPart: Option[String],
  val displayId: String
) extends TextDisplay {

  private def copy(
    contentOrUrl: Either[URL, String] = contentOrUrl,
    vendorPart: Option[String] = vendorPart
  ): Json =
    new Json(contentOrUrl, vendorPart, displayId)

  def withContent(code: String): Json =
    copy(contentOrUrl = Right(code))
  def withUrl(url: String): Json =
    copy(contentOrUrl = Left(new URL(url)))
  def withUrl(url: URL): Json =
    copy(contentOrUrl = Left(url))
  def withSubType(subType: String): Json =
    copy(vendorPart = Some(subType))
  def withVendorPart(vendorPartOpt: Option[String]): Json =
    copy(vendorPart = vendorPartOpt)

  def mimeType: String = {
    val vendorPart0 = vendorPart.fold("")(_ + "+")
    Json.mimeType(vendorPart0)
  }

  def data(): Map[String, String] =
    Map(mimeType -> finalContent)

}

object Json extends TextDisplay.Builder[Json] {
  protected def build(contentOrUrl: Either[URL, String]): Json =
    new Json(contentOrUrl, None, UpdatableDisplay.generateId())

  def mimeType                     = "application/json"
  def mimeType(vendorPart: String) = s"application/${vendorPart}json"
}
