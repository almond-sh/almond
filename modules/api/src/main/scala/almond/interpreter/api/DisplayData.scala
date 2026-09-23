package almond.interpreter.api

import almond.interpreter.api.internal.DisplayDataCompat

import java.util.Base64

import scala.collection.immutable.ListMap

/** Data that can be pushed to and displayed in the Jupyter UI */
final class DisplayData private (private val impl: DisplayData.Impl)
    extends Product with Serializable with DisplayDataCompat {

  import DisplayData.Value

  @deprecated("Prefer using DisplayData(), then calling with* methods on it", "0.14.2")
  def this(
    data: Map[String, String],
    metadata: Map[String, String] = Map.empty,
    idOpt: Option[String] = None
  ) =
    this(
      DisplayData.Impl(
        ListMap(data.map { case (k, v) => (k, DisplayData.Value.String(v)) }.toSeq: _*),
        ListMap(metadata.map { case (k, v) => (k, DisplayData.Value.String(v)) }.toSeq: _*),
        idOpt
      )
    )

  override def equals(other: Any): Boolean =
    other.isInstanceOf[DisplayData] &&
    impl == other.asInstanceOf[DisplayData].impl
  override def hashCode(): Int =
    "DisplayData".## + 36 * impl.hashCode()
  override def toString(): String =
    productIterator.mkString(productPrefix + "(", ", ", ")")

  def canEqual(that: Any): Boolean =
    that.isInstanceOf[DisplayData]

  // might go away, added for binary compatibility
  def productArity: Int =
    impl.productArity

  // might go away, added for binary compatibility
  def productElement(n: Int): Any =
    impl.productElement(n)

  // FIXME Only for 2.13 (and 3?)
  // override def productElementName(n: Int): String =
  //   impl.productElementName(n)
  // override def productElementNames: Iterator[String] =
  //   impl.productElementNames

  // might go away, added for binary compatibility
  override def productIterator: Iterator[Any] =
    impl.productIterator

  // might go away, added for binary compatibility
  override def productPrefix: String =
    "DisplayData"

  @deprecated(
    "Use detailedData instead, that preserves the nature - string or JSON - of values",
    "0.14.2"
  )
  def data: Map[String, String] =
    data0
  protected def data0: Map[String, String] =
    impl.data.map {
      case (k, v: Value.String) => (k, v.value)
      case (k, v: Value.Json)   => (k, v.value)
    }
  @deprecated(
    "Use detailedMetadata instead, that preserves the nature - string or JSON - of values",
    "0.14.2"
  )
  def metadata: Map[String, String] =
    metadata0
  protected def metadata0: Map[String, String] =
    impl.metadata.map {
      case (k, v: Value.String) => (k, v.value)
      case (k, v: Value.Json)   => (k, v.value)
    }

  /** The data to be displayed
    *
    * Keys are a MIME type, values are either a string (via `Value.String`) or JSON (via
    * `Value.Json`)
    */
  def detailedData: ListMap[String, Value] =
    impl.data

  /** Metadata associated to this display data */
  def detailedMetadata: ListMap[String, Value] =
    impl.metadata

  /** Id associated to this display data, if any
    *
    * Pushing a display data that has the same id as a previous one updates the previous one in the
    * front-end. This allows to push updates for display data.
    */
  def idOpt: Option[String] =
    impl.idOpt

  @deprecated(
    "Use withDetailedData instead, that preserves the nature - string or JSON - of values",
    "0.14.2"
  )
  def withData(data: Map[String, String]): DisplayData =
    withData0(data)
  private def withData0(data: Map[String, String]): DisplayData =
    new DisplayData(
      impl.copy(
        data = ListMap(
          data.map { case (k, v) => (k, Value.String(v)) }.toSeq: _*
        )
      )
    )
  @deprecated(
    "Use withDetailedData instead, that preserves the nature - string or JSON - of values",
    "0.14.2"
  )
  def withMetadata(metadata: Map[String, String]): DisplayData =
    withMetadata0(metadata)
  private def withMetadata0(metadata: Map[String, String]): DisplayData =
    new DisplayData(
      impl.copy(
        metadata = ListMap(
          metadata.map { case (k, v) => (k, Value.String(v)) }.toSeq: _*
        )
      )
    )

  /** Updates the data of this display data
    *
    * @return
    *   A new `DisplayData` instance, with the new data passed to this method
    */
  def withDetailedData(data: Map[String, Value]): DisplayData =
    new DisplayData(impl.copy(data = ListMap(data.toSeq: _*)))

  /** Updates the data of this display data
    *
    * @return
    *   A new `DisplayData` instance, with the new data passed to this method
    */
  def withDetailedData(data: Seq[(String, Value)]): DisplayData =
    new DisplayData(impl.copy(data = ListMap(data.toSeq: _*)))

  /** Updates the data of this display data
    *
    * @return
    *   A new `DisplayData` instance, with the new data passed to this method
    */
  def withDetailedData(data: ListMap[String, Value]): DisplayData =
    new DisplayData(impl.copy(data = data))

  /** Updates the metadata of this display data
    *
    * @return
    *   A new `DisplayData` instance, with the new metadata passed to this method
    */
  def withDetailedMetadata(metadata: Map[String, Value]): DisplayData =
    new DisplayData(impl.copy(metadata = ListMap(metadata.toSeq: _*)))

  /** Updates the metadata of this display data
    *
    * @return
    *   A new `DisplayData` instance, with the new metadata passed to this method
    */
  def withDetailedMetadata(metadata: Seq[(String, Value)]): DisplayData =
    new DisplayData(impl.copy(metadata = ListMap(metadata.toSeq: _*)))

  /** Updates the metadata of this display data
    *
    * @return
    *   A new `DisplayData` instance, with the new metadata passed to this method
    */
  def withDetailedMetadata(metadata: ListMap[String, Value]): DisplayData =
    new DisplayData(impl.copy(metadata = metadata))

  /** Updates the id of this display data
    *
    * @return
    *   A new `DisplayData` instance, with the new id passed to this method
    */
  def withId(idOpt: Option[String]): DisplayData =
    new DisplayData(impl.copy(idOpt = idOpt))

  /** Updates the id of this display data
    *
    * @return
    *   A new `DisplayData` instance, with the new id passed to this method
    */
  def withId(id: String): DisplayData =
    new DisplayData(impl.copy(idOpt = Some(id)))

  /** Adds new string-based data to be displayed
    *
    * @param mimeType
    *   MIME type of the addional data
    * @param data
    *   content of the data, as a string
    * @return
    *   A new `DisplayData` instance, with the new data added to it
    */
  def add(mimeType: String, data: String): DisplayData =
    new DisplayData(impl.copy(data = impl.data + (mimeType -> Value.String(data))))

  /** Adds new JSON-based data to be displayed
    *
    * @param mimeType
    *   MIME type of the addional data
    * @param data
    *   JSON content of the data, stringified
    * @return
    *   A new `DisplayData` instance, with the new data added to it
    */
  def addStringifiedJson(mimeType: String, data: String): DisplayData =
    new DisplayData(impl.copy(data = impl.data + (mimeType -> Value.Json(data))))

  /** Whether this `DisplayData` is empty */
  def isEmpty: Boolean =
    impl.data.isEmpty && impl.metadata.isEmpty && idOpt.isEmpty

  /** Pushes this `DisplayData` to the front-end, to be displayed */
  def show()(implicit outputHandler: OutputHandler): Unit =
    outputHandler.display(this)

  /** Pushes this `DisplayData` to the front-end as an update
    *
    * This should update former `DisplayData` with the same id.
    */
  def update()(implicit outputHandler: OutputHandler): Unit =
    outputHandler.updateDisplay(this)

  @deprecated("Prefer using the with* methods", "0.14.2")
  def copy(
    data: Map[String, String] = data0,
    metadata: Map[String, String] = metadata0,
    idOpt: Option[String] = idOpt
  ): DisplayData =
    withData0(data).withMetadata0(metadata).withId(idOpt)
}

object DisplayData extends DisplayDataCompat.Companion {

  /** A value that can either an actual `String`, or stringified JSON
    *
    * Discriminating actual strings from stringified JSON allows to handle them differently when
    * serializing the value as JSON: actual strings should be serialized as JSON strings, while
    * stringified JSON should be used as-is when serializing the value as JSON.
    */
  sealed abstract class Value extends Product with Serializable {
    def asString: Option[String]
  }
  object Value {

    /** An actual string, meant to be serialized as a JSON string */
    final case class String(value: java.lang.String) extends Value {
      def asString: Option[java.lang.String] = Some(value)
    }

    /** Stringified JSON, meant to be used as-is when serializing this value as JSON */
    final case class Json(value: java.lang.String) extends Value {
      def asString: Option[java.lang.String] = None
    }
  }

  val empty: DisplayData =
    new DisplayData(Impl(ListMap.empty, ListMap.empty, None))

  /** Creates a new initially empty `DisplayData` */
  def apply(): DisplayData =
    empty

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
        ListMap(data.map { case (k, v) => (k, Value.String(v)) }.toSeq: _*),
        ListMap(metadata.map { case (k, v) => (k, Value.String(v)) }.toSeq: _*),
        idOpt
      )
    )

  private final case class Impl(
    data: ListMap[String, Value],
    metadata: ListMap[String, Value],
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

  protected def data0(data: DisplayData): Map[String, String] =
    data.data0
  protected def metadata0(data: DisplayData): Map[String, String] =
    data.metadata0
  protected def withData0(displayData: DisplayData, data: Map[String, String]): DisplayData =
    displayData.withData0(data)
  protected def withMetadata0(
    displayData: DisplayData,
    metadata: Map[String, String]
  ): DisplayData =
    displayData.withMetadata0(metadata)
}
