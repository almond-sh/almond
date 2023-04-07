package almond.interpreter.api

import java.util.Base64

/** Data that can be pushed to and displayed in the Jupyter UI */
final case class DisplayData(
  data: Map[String, String],
  metadata: Map[String, String] = Map.empty,
  idOpt: Option[String] = None
) {
  def isEmpty: Boolean =
    data.isEmpty && metadata.isEmpty && idOpt.isEmpty
  def withId(id: String): DisplayData =
    copy(idOpt = Some(id))

  def show()(implicit outputHandler: OutputHandler): Unit =
    outputHandler.display(this)
  def update()(implicit outputHandler: OutputHandler): Unit =
    outputHandler.updateDisplay(this)
}

object DisplayData {

  object ContentType {
    def text     = "text/plain"
    def markdown = "text/markdown"
    def html     = "text/html"
    def latex    = "text/latex"
    def js       = "application/javascript"
    def jpg      = "image/jpeg"
    def png      = "image/png"
    def gif      = "image/gif"
    def svg      = "image/svg+xml"
  }

  def text(text: String): DisplayData =
    DisplayData(Map(ContentType.text -> text))
  def markdown(content: String): DisplayData =
    DisplayData(Map(ContentType.markdown -> content))
  def html(content: String): DisplayData =
    DisplayData(Map(ContentType.html -> content))
  def latex(content: String): DisplayData =
    DisplayData(Map(ContentType.latex -> content))
  def js(content: String): DisplayData =
    DisplayData(Map(ContentType.js -> content))
  def jpg(content: Array[Byte]): DisplayData =
    DisplayData(Map(ContentType.jpg -> Base64.getEncoder.encodeToString(content)))
  def png(content: Array[Byte]): DisplayData =
    DisplayData(Map(ContentType.png -> Base64.getEncoder.encodeToString(content)))
  def svg(content: String): DisplayData =
    DisplayData(Map(ContentType.svg -> content))

  val empty: DisplayData =
    DisplayData(Map.empty)

  implicit class DisplayDataSyntax(private val s: String) extends AnyVal {
    def asText: DisplayData =
      DisplayData(Map(ContentType.text -> s))
    def as(mimeType: String): DisplayData =
      DisplayData(Map(mimeType -> s))
    def asMarkdown: DisplayData =
      DisplayData(Map(ContentType.markdown -> s))
    def asHtml: DisplayData =
      DisplayData(Map(ContentType.html -> s))
    def asLatex: DisplayData =
      DisplayData(Map(ContentType.latex -> s))
    def asJs: DisplayData =
      DisplayData(Map(ContentType.js -> s))
  }

}
