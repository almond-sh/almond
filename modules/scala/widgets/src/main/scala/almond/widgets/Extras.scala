package almond.widgets

import almond.display.Display
import almond.interpreter.api.{DisplayData, OutputHandler}

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream, PrintWriter, StringWriter}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.Locale

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

// Things added to some of the widgets generated from the ipywidgets model schema

/** Clicks on buttons */
trait ButtonExtras { self: Button =>

  /** Calls `f` each time the button is clicked */
  final def onClick(f: => Unit): Subscription =
    onMessage { (content, _) =>
      content match {
        case obj: JsonValue.Obj if obj.get("event").contains(JsonValue.Str("click")) =>
          f
        case _ =>
      }
    }

  /** Simulates a click on the button: calls the callbacks passed to [[onClick]] */
  final def click(): Unit =
    self.handleMessage(
      JsonValue.Obj(
        "method"  -> JsonValue.Str("custom"),
        "content" -> JsonValue.Obj("event" -> JsonValue.Str("click"))
      ),
      Nil
    )
}

/** Writing to output widgets from the kernel
  *
  * Output widgets display outputs, like the output area of cells: text written to stdout or stderr,
  * rich displays (HTML, images, …), errors. These are added from the kernel, by sending the outputs
  * as part of the widget state. This works from cells, but also from widget callbacks, and from any
  * thread.
  */
trait OutputExtras { self: Output =>

  private def appendOutput(output: JsonValue.Obj): Unit =
    holdSync {
      val outputs0 = outputs()
      // merging consecutive stream outputs of the same stream, like the Jupyter UIs do
      val merged = (outputs0.lastOption, output.get("output_type")) match {
        case (Some(last: JsonValue.Obj), Some(JsonValue.Str("stream")))
            if last.get("output_type").contains(JsonValue.Str("stream")) &&
            last.get("name") == output.get("name") =>
          val text = (last.get("text"), output.get("text")) match {
            case (Some(JsonValue.Str(a)), Some(JsonValue.Str(b))) => a + b
            case _                                                => ""
          }
          Some(outputs0.init :+ (last + ("text" -> JsonValue.Str(text))))
        case _ =>
          None
      }
      outputs() = merged.getOrElse(outputs0 :+ output)
    }

  private def appendStream(name: String, text: String): Unit =
    if (text.nonEmpty)
      appendOutput(
        JsonValue.Obj(
          "output_type" -> JsonValue.Str("stream"),
          "name"        -> JsonValue.Str(name),
          "text"        -> JsonValue.Str(text)
        )
      )

  /** Appends text to the stdout output of this widget */
  final def appendStdout(text: String): Unit =
    appendStream("stdout", text)

  /** Appends text to the stderr output of this widget */
  final def appendStderr(text: String): Unit =
    appendStream("stderr", text)

  /** Appends a rich display (HTML, image, …) to this widget */
  final def appendDisplayData(data: DisplayData): Unit = {
    def toJson(values: ListMap[String, DisplayData.Value]): JsonValue =
      JsonValue.Obj(values.map {
        case (k, DisplayData.Value.String(v)) => k -> (JsonValue.Str(v): JsonValue)
        case (k, DisplayData.Value.Json(v))   => k -> JsonValue.parse(v)
      })
    appendOutput(
      JsonValue.Obj(
        "output_type" -> JsonValue.Str("display_data"),
        "data"        -> toJson(data.detailedData),
        "metadata"    -> toJson(data.detailedMetadata)
      )
    )
  }

  /** Appends a rich display (HTML, image, widget, …) to this widget */
  final def appendDisplay(display: Display): Unit =
    appendDisplayData(display.displayData())

  /** Appends an exception to this widget, like errors are shown in cell outputs */
  final def appendException(exception: Throwable): Unit = {
    val sw = new StringWriter
    exception.printStackTrace(new PrintWriter(sw))
    appendOutput(
      JsonValue.Obj(
        "output_type" -> JsonValue.Str("error"),
        "ename"       -> JsonValue.Str(exception.getClass.getName),
        "evalue"      -> JsonValue.Str(Option(exception.getMessage).getOrElse("")),
        "traceback"   -> JsonValue.Arr(sw.toString.linesIterator.map(JsonValue.Str(_)).toVector)
      )
    )
  }

  /** Clears the outputs of this widget */
  final def clearOutput(): Unit =
    outputs() = Nil

  /** An [[OutputHandler]] sending what it's given to this widget
    *
    * Pass it explicitly to display things in this widget, like
    * {{{
    *   Html("<b>Hello</b>").display()(out.outputHandler)
    * }}}
    */
  final lazy val outputHandler: OutputHandler =
    new OutputHandler {
      def stdout(s: String): Unit                      = appendStdout(s)
      def stderr(s: String): Unit                      = appendStderr(s)
      def display(displayData: DisplayData): Unit      = appendDisplayData(displayData)
      def clearOutput(waitForNewOutput: Boolean): Unit = self.clearOutput()
      def updateDisplay(displayData: DisplayData): Unit =
        throw new UnsupportedOperationException("Updating displays in output widgets")
      def addPayload(payload: String): Unit = ()
      def canOutput(): Boolean              = true
      def messageIdOpt: Option[String]      = None
    }

  private def printStream(append: String => Unit): PrintStream = {
    val stream = new OutputStream {
      private val buffer = new ByteArrayOutputStream
      def write(b: Int): Unit =
        buffer.synchronized(buffer.write(b))
      override def write(b: Array[Byte], off: Int, len: Int): Unit =
        buffer.synchronized(buffer.write(b, off, len))
      override def flush(): Unit = {
        val s = buffer.synchronized {
          val s = new String(buffer.toByteArray, StandardCharsets.UTF_8)
          buffer.reset()
          s
        }
        append(s)
      }
    }
    new PrintStream(stream, true, "UTF-8")
  }

  /** Runs `f`, sending what it prints to this widget
    *
    * What `println`, `Console.out` and `Console.err` print in `f` gets sent to this widget. If `f`
    * throws, the exception is shown in this widget, and isn't re-thrown, like `with output_widget:`
    * blocks do in Python.
    *
    * Use [[outputHandler]] to display rich outputs (HTML, images, …) in this widget.
    *
    * Note that this only redirects the current thread. What's printed via `System.out` or
    * `System.err` isn't captured.
    */
  final def capture(f: => Unit): Unit = {
    val out = printStream(appendStdout)
    val err = printStream(appendStderr)
    try
      Console.withOut(out) {
        Console.withErr(err) {
          f
        }
      }
    catch {
      case NonFatal(e) =>
        out.flush()
        err.flush()
        appendException(e)
    }
    finally {
      out.flush()
      err.flush()
    }
  }
}

/** Front-end counterpart of a file uploaded via a [[FileUpload]] widget */
final case class UploadedFile(
  name: String,
  mimeType: String,
  size: Long,
  content: Array[Byte],
  lastModified: Instant
) {
  override def toString: String =
    s"UploadedFile($name, $mimeType, $size bytes, last modified $lastModified)"
}

object UploadedFile {
  implicit val codec: Codec[UploadedFile] =
    new Codec[UploadedFile] {
      def encode(file: UploadedFile): JsonValue =
        JsonValue.Obj(
          "name"          -> JsonValue.Str(file.name),
          "type"          -> JsonValue.Str(file.mimeType),
          "size"          -> JsonValue.Num(file.size.toDouble),
          "content"       -> JsonValue.Bytes(file.content),
          "last_modified" -> JsonValue.Num(file.lastModified.toEpochMilli.toDouble)
        )
      def decode(json: JsonValue): UploadedFile =
        json match {
          case obj: JsonValue.Obj =>
            def str(key: String) = obj.get(key).map(Codec.optionalString.decode).getOrElse("")
            UploadedFile(
              str("name"),
              str("type"),
              obj.get("size").map(Codec.long.decode).getOrElse(0L),
              obj.get("content").map(Codec.bytes.decode).getOrElse(Array.emptyByteArray),
              Instant.ofEpochMilli(obj.get("last_modified").map(Codec.long.decode).getOrElse(0L))
            )
          case _ =>
            throw new IllegalArgumentException(s"Expected an uploaded file, got ${json.render}")
        }
    }
}

/** Helpers to create images, videos, or audio widgets from files or URLs */
trait MediaCompanion[W] {
  protected def fromBytes(value: Array[Byte], format: String): W

  /** Reads `path`, guessing the format from its extension */
  def fromFile(path: Path): W = {
    val name = path.getFileName.toString
    val ext = name.lastIndexOf('.') match {
      case -1  => ""
      case idx => name.substring(idx + 1).toLowerCase(Locale.ROOT)
    }
    val format = ext match {
      case "jpg" => "jpeg"
      case "svg" => "svg+xml"
      case "mp3" => "mpeg"
      case other => other
    }
    fromBytes(Files.readAllBytes(path), format)
  }

  def fromFile(path: String): W =
    fromFile(Paths.get(path))

  /** Has the front-end load the media from `url` */
  def fromUrl(url: String): W =
    fromBytes(url.getBytes(StandardCharsets.UTF_8), "url")

  def fromUrl(url: URI): W =
    fromUrl(url.toASCIIString)
}

/** A widget whose model isn't known in advance
  *
  * Its state can be read and written via [[field]]. This is used for widgets created by the
  * front-end with an unknown model, and can be used for widgets of third-party libraries.
  */
final class GenericWidget(
  val modelName: String,
  val modelModule: String,
  val modelModuleVersion: String,
  val viewName: Option[String],
  val viewModule: String,
  val viewModuleVersion: String
) extends Widget with Display {

  /** A field of the state of this widget */
  def field[T](name: String)(implicit codec: Codec[T]): StateProp[T] =
    new StateProp(this, name, codec)

  def data(): Map[String, String] =
    Map(DisplayData.ContentType.text -> toString)

  override def displayData(): DisplayData =
    WidgetDisplay.displayData(this)

  override def toString: String =
    s"GenericWidget($modelModule:$modelName, $id)"
}

/** Base class for widgets based on [anywidget](https://anywidget.dev)
  *
  * The [[esm]] JavaScript module renders the widget in the front-end. Add properties synced with
  * the front-end with `prop`, like
  * {{{
  *   class Counter extends AnyWidget(
  *     esm = """
  *       function render({ model, el }) {
  *         let button = document.createElement("button");
  *         const update = () => { button.innerHTML = `count is ${model.get("count")}`; };
  *         button.addEventListener("click", () => {
  *           model.set("count", model.get("count") + 1);
  *           model.save_changes();
  *         });
  *         model.on("change:count", update);
  *         update();
  *         el.appendChild(button);
  *       }
  *       export default { render };
  *     """
  *   ) {
  *     val count = prop("count", 0)
  *   }
  * }}}
  *
  * The `anywidget` Python package needs to be installed alongside Jupyter, for its front-end
  * extension to be available.
  *
  * @param esm
  *   JavaScript module, as code, or as a URL
  * @param css
  *   CSS to add to the page, if any
  */
abstract class AnyWidget(esm: String, css: String = "") extends DOMWidget {
  def modelName          = "AnyModel"
  def modelModule        = "anywidget"
  def modelModuleVersion = AnyWidget.version
  def viewName           = Some("AnyView")
  def viewModule         = "anywidget"
  def viewModuleVersion  = AnyWidget.version

  val layout: StateProp[Layout] = prop("layout", Layout())(Codec.widget[Layout])

  /** The JavaScript module of this widget, that can be changed to update its views */
  val esmProp: StateProp[String] = prop("_esm", esm)

  locally {
    if (css.nonEmpty)
      prop("_css", css)
    prop("_anywidget_id", getClass.getName)
  }
}

object AnyWidget {

  /** The version range of the anywidget front-end extension we accept
    *
    * The protocol between anywidget modules and their front-end extension has been stable since
    * anywidget 0.9, so we accept any version.
    */
  def version = "*"
}

private[widgets] object WidgetDisplay {
  def displayData(widget: Widget): DisplayData = {
    widget.open()
    val viewData = JsonValue.Obj(
      "version_major" -> JsonValue.Num(2),
      "version_minor" -> JsonValue.Num(0),
      "model_id"      -> JsonValue.Str(widget.id)
    )
    DisplayData()
      .add(DisplayData.ContentType.text, widget.toString)
      .addStringifiedJson(Widget.viewMimeType, viewData.render)
  }
}
