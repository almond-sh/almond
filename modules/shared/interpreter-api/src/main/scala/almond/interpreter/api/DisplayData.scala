package almond.interpreter.api

import java.util.Base64

final class DisplayData private (private val impl: DisplayData.Impl)
    extends Product with Serializable {

  import DisplayData.Value

  override def equals(other: Any): Boolean =
    other.isInstanceOf[DisplayData] &&
    impl == other.asInstanceOf[DisplayData].impl
  override def hashCode(): Int =
    "DisplayData".## + 36 * impl.hashCode()
  override def toString(): String =
    s"DisplayData($impl)"

  def canEqual(that: Any): Boolean =
    that.isInstanceOf[DisplayData]
  def productArity: Int =
    impl.productArity
  def productElement(n: Int): Any =
    impl.productElement(n)
  // FIXME Only for 2.13 (and 3?)
  // override def productElementName(n: Int): String =
  //   impl.productElementName(n)
  // override def productElementNames: Iterator[String] =
  //   impl.productElementNames
  override def productIterator: Iterator[Any] =
    impl.productIterator
  override def productPrefix: String =
    "DisplayData"

  @deprecated(
    "Use detailedData instead, that preserves the nature - string or JSON - of values",
    "0.14.2"
  )
  def data: Map[String, String] =
    impl.data.map {
      case (k, v: Value.String) => (k, v.value)
      case (k, v: Value.Json)   => (k, v.value)
    }
  @deprecated(
    "Use detailedMetadata instead, that preserves the nature - string or JSON - of values",
    "0.14.2"
  )
  def metadata: Map[String, String] =
    impl.metadata.map {
      case (k, v: Value.String) => (k, v.value)
      case (k, v: Value.Json)   => (k, v.value)
    }
  def detailedData: Map[String, Value] =
    impl.data
  def detailedMetadata: Map[String, Value] =
    impl.metadata
  def idOpt: Option[String] =
    impl.idOpt

  @deprecated(
    "Use withDetailedData instead, that preserves the nature - string or JSON - of values",
    "0.14.2"
  )
  def withData(data: Map[String, String]): DisplayData =
    new DisplayData(impl.copy(data = data.map { case (k, v) => (k, Value.String(v)) }))
  @deprecated(
    "Use withDetailedData instead, that preserves the nature - string or JSON - of values",
    "0.14.2"
  )
  def withMetadata(metadata: Map[String, String]): DisplayData =
    new DisplayData(impl.copy(metadata = metadata.map { case (k, v) => (k, Value.String(v)) }))
  def withDetailedData(data: Map[String, Value]): DisplayData =
    new DisplayData(impl.copy(data = data))
  def withDetailedMetadata(metadata: Map[String, Value]): DisplayData =
    new DisplayData(impl.copy(metadata = metadata))
  def withId(idOpt: Option[String]): DisplayData =
    new DisplayData(impl.copy(idOpt = idOpt))
  def withId(id: String): DisplayData =
    new DisplayData(impl.copy(idOpt = Some(id)))

  def add(mimeType: String, data: String): DisplayData =
    new DisplayData(impl.copy(data = impl.data + (mimeType -> Value.String(data))))
  def addStringifiedJson(mimeType: String, data: String): DisplayData =
    new DisplayData(impl.copy(data = impl.data + (mimeType -> Value.Json(data))))

  def isEmpty: Boolean =
    impl.data.isEmpty && impl.metadata.isEmpty && idOpt.isEmpty

  def show()(implicit outputHandler: OutputHandler): Unit =
    outputHandler.display(this)
  def update()(implicit outputHandler: OutputHandler): Unit =
    outputHandler.updateDisplay(this)
}

object DisplayData {

  sealed abstract class Value extends Product with Serializable {
    def asString: Option[String]
  }
  object Value {
    final case class String(value: java.lang.String) extends Value {
      def asString: Option[java.lang.String] = Some(value)
    }
    final case class Json(value: java.lang.String) extends Value {
      def asString: Option[java.lang.String] = None
    }
  }

  def apply(): DisplayData =
    new DisplayData(Impl(Map.empty, Map.empty, None))

  @deprecated(
    "Prefer the constructor with no argument, then calling add and addStringifiedJson on it",
    "0.14.2"
  )
  def apply(
    data: Map[String, String],
    metadata: Map[String, String] = Map.empty,
    idOpt: Option[String] = None
  ): DisplayData =
    new DisplayData(
      Impl(
        data.map { case (k, v) => (k, Value.String(v)) },
        metadata.map { case (k, v) => (k, Value.String(v)) },
        idOpt
      )
    )

  private final case class Impl(
    data: Map[String, Value],
    metadata: Map[String, Value],
    idOpt: Option[String]
  )

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

  val empty: DisplayData =
    DisplayData()

  def text(text: String): DisplayData =
    empty.add(ContentType.text, text)
  def markdown(content: String): DisplayData =
    empty.add(ContentType.markdown, content)
  def html(content: String): DisplayData =
    empty.add(ContentType.html, content)
  def latex(content: String): DisplayData =
    empty.add(ContentType.latex, content)
  def js(content: String): DisplayData =
    empty.add(ContentType.js, content)
  def jpg(content: Array[Byte]): DisplayData =
    empty.add(ContentType.jpg, Base64.getEncoder.encodeToString(content))
  def png(content: Array[Byte]): DisplayData =
    empty.add(ContentType.png, Base64.getEncoder.encodeToString(content))
  def svg(content: String): DisplayData =
    empty.add(ContentType.svg, content)

  implicit class DisplayDataSyntax(private val s: String) extends AnyVal {
    def asText: DisplayData =
      empty.add(ContentType.text, s)
    def as(mimeType: String): DisplayData =
      empty.add(mimeType, s)
    def asMarkdown: DisplayData =
      empty.add(ContentType.markdown, s)
    def asHtml: DisplayData =
      empty.add(ContentType.html, s)
    def asLatex: DisplayData =
      empty.add(ContentType.latex, s)
    def asJs: DisplayData =
      empty.add(ContentType.js, s)
  }

}
