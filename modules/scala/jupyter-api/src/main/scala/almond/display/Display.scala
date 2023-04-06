package almond.display

import java.io.File
import java.net.URL
import java.nio.file.Path
import java.util.{Map => JMap}

import almond.interpreter.api.{DisplayData, OutputHandler}
import jupyter.{Displayer, Displayers}

import scala.collection.JavaConverters._

trait Display {
  def data(): Map[String, String]
  def metadata(): Map[String, String] = Map()
  def displayData(): DisplayData =
    DisplayData(data(), metadata = metadata())

  def display()(implicit output: OutputHandler): Unit =
    output.display(displayData())

  // registering things with jvm-repr just in case
  Display.registered
}

object Display {

  private lazy val registered: Unit =
    Displayers.register(
      classOf[Display],
      new Displayer[Display] {
        def display(d: Display): JMap[String, String] =
          d.data().asJava
      }
    )

  def markdown(content: String)(implicit output: OutputHandler): Unit =
    Markdown(content).display()
  def html(content: String)(implicit output: OutputHandler): Unit =
    Html(content).display()
  def latex(content: String)(implicit output: OutputHandler): Unit =
    Latex(content).display()
  def text(content: String)(implicit output: OutputHandler): Unit =
    Text(content).display()

  def js(content: String)(implicit output: OutputHandler): Unit =
    Javascript(content).display()

  def svg(content: String)(implicit output: OutputHandler): Unit =
    Svg(content).display()

  trait Builder[C, T] {

    protected def build(contentOrUrl: Either[URL, C]): T

    def apply(content: C): T =
      build(Right(content))

    def from(url: String): T =
      build(Left(new URL(url)))
    def from(url: URL): T =
      build(Left(url))

    def fromFile(file: File): T =
      build(Left(file.toURI.toURL))
    def fromFile(path: Path): T =
      build(Left(path.toUri.toURL))
    def fromFile(path: String): T =
      build(Left(new File(path).toURI.toURL))
  }

}
