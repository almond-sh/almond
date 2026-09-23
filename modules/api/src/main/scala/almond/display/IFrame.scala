package almond.display

import java.net.URLEncoder

final class IFrame private (
  val src: String,
  val width: Option[String],
  val height: Option[String],
  val params: Seq[(String, String)],
  val displayId: String
) extends UpdatableDisplay {

  private def copy(
    src: String = src,
    width: Option[String] = width,
    height: Option[String] = height,
    params: Seq[(String, String)] = params
  ): IFrame =
    new IFrame(src, width, height, params, displayId)

  def withSrc(src: String): IFrame =
    copy(src = src)
  def withWidth(width: Int): IFrame =
    copy(width = Some(width.toString))
  def withWidth(width: String): IFrame =
    copy(width = Some(width))
  def withWidth(widthOpt: Option[String]): IFrame =
    copy(width = widthOpt)
  def withHeight(height: Int): IFrame =
    copy(height = Some(height.toString))
  def withHeight(height: String): IFrame =
    copy(height = Some(height))
  def withHeight(heightOpt: Option[String]): IFrame =
    copy(height = heightOpt)

  private def html: String = {
    val widthPart  = width.fold("")(w => s"""width="$w"""")
    val heightPart = height.fold("")(h => s"""height="$h"""")
    val url = src + {
      if (params.isEmpty) ""
      else {
        def encode(s: String): String =
          URLEncoder.encode(s, "UTF-8")
            .replaceAll("\\+", "%20")
        params
          .map { case (k, v) => s"${encode(k)}=${encode(v)}" }
          .mkString("?", "&", "")
      }
    }
    // FIXME Escaping of width / height
    s"""<iframe
       |  $widthPart
       |  $heightPart
       |  src="$url"
       |>
       |</iframe>
     """.stripMargin
  }

  def data(): Map[String, String] =
    Map(Html.mimeType -> html)

}

object IFrame {
  def apply(src: String): IFrame =
    new IFrame(src, None, None, Nil, UpdatableDisplay.generateId())
}
