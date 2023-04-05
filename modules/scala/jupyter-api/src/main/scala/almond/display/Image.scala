package almond.display

import java.awt.image.RenderedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.net.{HttpURLConnection, URL, URLConnection}
import java.util.Base64

import javax.imageio.ImageIO

import scala.util.Try

final class Image private (
  val width: Option[String],
  val height: Option[String],
  val format: Option[Image.Format],
  byteArrayOrUrl: Either[URL, Array[Byte]],
  val embed: Boolean,
  val displayId: String
) extends UpdatableDisplay {

  def byteArrayOpt: Option[Array[Byte]] =
    byteArrayOrUrl.toOption
  def urlOpt: Option[URL] =
    byteArrayOrUrl.left.toOption

  private def copy(
    width: Option[String] = width,
    height: Option[String] = height,
    format: Option[Image.Format] = format,
    byteArrayOrUrl: Either[URL, Array[Byte]] = byteArrayOrUrl,
    embed: Boolean = embed
  ): Image =
    new Image(
      width,
      height,
      format,
      byteArrayOrUrl,
      embed,
      displayId
    )

  def withByteArray(data: Array[Byte]): Image =
    copy(byteArrayOrUrl = Right(data))
  def withUrl(url: URL): Image =
    copy(byteArrayOrUrl = Left(url))
  def withUrl(url: String): Image =
    copy(byteArrayOrUrl = Left(new URL(url)))
  def withHeight(height: Int): Image =
    copy(height = Some(height.toString))
  def withHeight(heightOpt: Option[String]): Image =
    copy(height = heightOpt)
  def withWidth(width: Int): Image =
    copy(width = Some(width.toString))
  def withWidth(widthOpt: Option[String]): Image =
    copy(width = widthOpt)
  def withFormat(format: Image.Format): Image =
    copy(format = Some(format))
  def withFormat(formatOpt: Option[Image.Format]): Image =
    copy(format = formatOpt)
  def withEmbed(): Image =
    copy(embed = true)
  def withEmbed(embed: Boolean): Image =
    copy(embed = embed)

  override def metadata(): Map[String, String] =
    Map() ++
      width.map("width" -> _) ++
      height.map("height" -> _)

  def data(): Map[String, String] =
    byteArrayOrUrl match {
      case Left(url) =>
        if (embed) {
          val (contentTypeOpt, b) = Image.urlContent(url)
          val contentType = format
            .map(_.contentType)
            .orElse(contentTypeOpt)
            .orElse(Option(URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(b))))
            .getOrElse {
              throw new Exception(
                s"Cannot detect format or unrecognizable format for image at $url"
              )
            }

          if (!Image.imageTypes.contains(contentType))
            throw new IOException("Unknown or unsupported content type: " + contentType)

          val b0 = Base64.getEncoder.encodeToString(b)
          Map(contentType -> b0)
        }
        else {
          val attrs = metadata().map { case (k, v) => s"$k=$v" }.mkString(" ")
          Map(Html.mimeType -> s"<img src='${url.toExternalForm}' $attrs />")
        }

      case Right(b) =>
        val contentType = format
          .map(_.contentType)
          .orElse(Option(URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(b))))
          .getOrElse {
            throw new Exception("Cannot detect image format or unrecognizable image format")
          }

        if (!Image.imageTypes.contains(contentType))
          throw new IOException("Unknown or unsupported content type: " + contentType)

        val b0 = Base64.getEncoder.encodeToString(b)
        Map(contentType -> b0)
    }

}

object Image extends Display.Builder[Array[Byte], Image] {

  def fromRenderedImage(image: RenderedImage): Image =
    fromRenderedImage(image, format = JPG)

  def fromRenderedImage(image: RenderedImage, format: Format): Image = {
    val output: ByteArrayOutputStream = new ByteArrayOutputStream()
    ImageIO.write(image, format.toString, output)
    new Image(
      width = Some(image.getWidth.toString),
      height = Some(image.getHeight.toString),
      format = Some(format),
      byteArrayOrUrl = Right(output.toByteArray),
      embed = true,
      displayId = UpdatableDisplay.generateId()
    )
  }

  protected def build(contentOrUrl: Either[URL, Array[Byte]]): Image =
    new Image(
      width = None,
      height = None,
      format = None,
      byteArrayOrUrl = contentOrUrl,
      embed = contentOrUrl.left.exists(_.getProtocol == "file"),
      displayId = UpdatableDisplay.generateId()
    )

  sealed abstract class Format(val contentType: String) extends Product with Serializable
  case object JPG                                       extends Format("image/jpeg")
  case object PNG                                       extends Format("image/png")
  case object GIF                                       extends Format("image/gif")

  private val imageTypes = Set(JPG, PNG, GIF).map(_.contentType)

  private def urlContent(url: URL): (Option[String], Array[Byte]) = {

    var conn: URLConnection = null
    val (rawContent, contentTypeOpt) =
      try {
        conn = url.openConnection()
        conn.setConnectTimeout(5000) // allow users to tweak that?
        val b = TextDisplay.readFully(conn.getInputStream)
        val contentTypeOpt0 = conn match {
          case conn0: HttpURLConnection =>
            Option(conn0.getContentType)
          case _ =>
            None
        }
        (b, contentTypeOpt0)
      }
      finally
        if (conn != null) {
          Try(conn.getInputStream.close())
          conn match {
            case conn0: HttpURLConnection =>
              Try(conn0.getErrorStream.close())
              Try(conn0.disconnect())
            case _ =>
          }
        }

    (contentTypeOpt, rawContent)
  }
}
