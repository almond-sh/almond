package almond.api.helpers

import java.io.{BufferedInputStream, IOException}
import java.net.{URL, URLConnection}
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Base64, Locale, UUID}

import almond.interpreter.api.DisplayData.ContentType
import almond.interpreter.api.{DisplayData, OutputHandler}

final class Display(id: String, contentType: String) {
  def update(content: String)(implicit outputHandler: OutputHandler): Unit =
    outputHandler.updateDisplay(
      DisplayData(Map(contentType -> content))
        .withId(id)
    )

  override def toString =
    s"$contentType #$id"
}

object Display {

  def useRandomIds(): Boolean =
    sys.props
      .get("almond.ids.random")
      .forall(s => s == "1" || s.toLowerCase(Locale.ROOT) == "true")

  private val idCounter = new AtomicInteger
  private val divCounter = new AtomicInteger

  def newId(): String =
    if (useRandomIds())
      UUID.randomUUID().toString
    else
      idCounter.incrementAndGet().toString

  def newDiv(prefix: String = "data-"): String =
    prefix + {
      if (useRandomIds())
        UUID.randomUUID().toString
      else
        divCounter.incrementAndGet().toString
    }


  def markdown(content: String)(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.markdown(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.markdown)
  }

  def html(content: String)(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.html(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.html)
  }

  def latex(content: String)(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.latex(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.latex)
  }

  def text(content: String)(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.text(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.text)
  }

  def js(content: String)(implicit outputHandler: OutputHandler): Unit =
    outputHandler.display(
      DisplayData.js(content)
    )

  def svg(content: String)(implicit outputHandler: OutputHandler): Display = {
    val id = newId()
    outputHandler.display(
      DisplayData.svg(content)
        .withId(id)
    )
    new Display(id, DisplayData.ContentType.svg)
  }

  object Image {

    sealed abstract class Format(val contentType: String) extends Product with Serializable
    case object JPG extends Format(ContentType.jpg)
    case object PNG extends Format(ContentType.png)
    case object GIF extends Format(ContentType.gif)

    private val imageTypes = Set(JPG, PNG, GIF).map(_.contentType)

    private def dimensionMetadata(width: Option[String], height: Option[String]): Map[String, String] =
      Map() ++
        width.map("width" -> _) ++
        height.map("height" -> _)

    def fromArray(
      content: Array[Byte],
      format: Format,
      width: Option[String] = None,
      height: Option[String] = None,
      id: String = newId()
    )(implicit outputHandler: OutputHandler): Display = {
      DisplayData(
        data = Map(format.contentType -> Base64.getEncoder.encodeToString(content)),
        metadata = dimensionMetadata(width, height),
        idOpt = Some(id)
      ).show()
      new Display(id, format.contentType)
    }

    def fromUrl(
      url: String,
      embed: Boolean = false,
      format: Option[Format] = None,
      width: Option[String] = None,
      height: Option[String] = None,
      id: String = newId()
    )(implicit outputHandler: OutputHandler): Display = {
      val connection = new URL(url).openConnection()
      connection.setConnectTimeout(5000)
      connection.connect()
      val contentType = format.map(_.contentType).getOrElse(connection.getContentType)
      val data = if (embed) {
        if(!imageTypes.contains(contentType))
          throw new IOException("Unknown or unsupported content type: " + contentType)
        val input = new BufferedInputStream(connection.getInputStream)
        val rawImage = Iterator.continually(input.read).takeWhile(_ != -1).map(_.toByte).toArray
        contentType -> Base64.getEncoder.encodeToString(rawImage)
      } else {
        val dimensionAttrs = dimensionMetadata(width, height).map{case (k,v) => s"$k=$v"}.mkString(" ")
        ContentType.html -> s"<img src='$url' $dimensionAttrs/>"
      }
      DisplayData(
        data = Map(data),
        metadata = dimensionMetadata(width, height),
        idOpt = Some(id)
      ).show()
      new Display(id, contentType)
    }

    def fromFile(
      path: String,
      format: Option[Format] = None,
      width: Option[String] = None,
      height: Option[String] = None,
      id: String = newId()
    )(implicit outputHandler: OutputHandler): Display = {
      val contentType = format.map(_.contentType).getOrElse(URLConnection.guessContentTypeFromName(path))
      if(!imageTypes.contains(contentType))
        throw new IOException("Unknown or unsupported content type: " + contentType)
      val imgPath = Paths.get(path)
      val content = Files.readAllBytes(imgPath)
      DisplayData(
        data = Map(contentType -> Base64.getEncoder.encodeToString(content)),
        metadata = dimensionMetadata(width, height),
        idOpt = Some(id)
      ).show()
      new Display(id, contentType)
    }
  }
}
