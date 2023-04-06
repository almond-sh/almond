package almond.display

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{HttpURLConnection, URL, URLConnection}
import java.nio.charset.{Charset, StandardCharsets}

import scala.util.Try

abstract class TextDisplay extends UpdatableDisplay {

  def contentOrUrl: Either[URL, String]

  def content: Option[String] = contentOrUrl.toOption
  def url: Option[URL]        = contentOrUrl.left.toOption

  def finalContent: String =
    contentOrUrl match {
      case Left(url) =>
        TextDisplay.urlContent(url)
      case Right(c) => c
    }

  def withContent(code: String): UpdatableDisplay
  def withUrl(url: String): UpdatableDisplay

}

object TextDisplay {

  type Builder[T] = Display.Builder[String, T]

  private[almond] def readFully(is: InputStream): Array[Byte] = {

    val buffer = new ByteArrayOutputStream
    val data   = Array.ofDim[Byte](16384)

    var nRead = 0
    while ({
      nRead = is.read(data, 0, data.length)
      nRead != -1
    })
      buffer.write(data, 0, nRead)

    buffer.flush()
    buffer.toByteArray
  }

  def urlContent(url: URL): String = {

    var conn: URLConnection = null
    val (rawContent, charsetOpt) =
      try {
        conn = url.openConnection()
        conn.setConnectTimeout(5000) // allow users to tweak that?
        val b = readFully(conn.getInputStream)
        val charsetOpt0 = conn match {
          case conn0: HttpURLConnection =>
            conn0
              .getContentType
              .split(';')
              .map(_.trim)
              .find(_.startsWith("charset="))
              .map(_.stripPrefix("charset="))
              .filter(Charset.isSupported)
              .map(Charset.forName)
          case _ =>
            None
        }
        (b, charsetOpt0)
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

    new String(rawContent, charsetOpt.getOrElse(StandardCharsets.UTF_8))
  }
}
