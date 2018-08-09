package almond.interpreter.api

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
    def text = "text/plain"
    def html = "text/html"
    def js = "application/javascript"
  }

  def text(text: String): DisplayData =
    DisplayData(Map(ContentType.text -> text))
  def html(content: String): DisplayData =
    DisplayData(Map(ContentType.html -> content))
  def js(content: String): DisplayData =
    DisplayData(Map(ContentType.js -> content))

  val empty: DisplayData =
    DisplayData(Map.empty)

  implicit class DisplayDataSyntax(private val s: String) extends AnyVal {
    def asText: DisplayData =
      DisplayData(Map(ContentType.text -> s))
    def as(mimeType: String): DisplayData =
      DisplayData(Map(mimeType -> s))
    def asHtml: DisplayData =
      DisplayData(Map(ContentType.html -> s))
    def asJs: DisplayData =
      DisplayData(Map(ContentType.js -> s))
  }

}
