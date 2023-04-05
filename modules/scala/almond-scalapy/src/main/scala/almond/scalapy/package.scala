package almond

import java.{util => ju}
import jupyter.{Displayer, Displayers}
import me.shadaj.scalapy.interpreter.CPythonInterpreter
import me.shadaj.scalapy.py
import me.shadaj.scalapy.py.{PyQuote, SeqConverters}
import scala.io.Source
import scala.jdk.CollectionConverters._

package object scalapy {
  CPythonInterpreter.execManyLines(Source.fromResource("format_display_data.py").mkString)

  def initDisplay: Unit = {
    Displayers.register(
      classOf[py.Any],
      new Displayer[py.Any] {
        def display(obj: py.Any): ju.Map[String, String] = {
          val (data, _) = formatDisplayData(obj)
          if (data.isEmpty) null else data.asJava
        }
      }
    )
  }

  private val pyFormatDisplayData = py.Dynamic.global.__almond_scalapy_format_display_data

  private def formatDisplayData(obj: py.Any): (Map[String, String], Map[String, String]) = {
    val displayData = pyFormatDisplayData(obj, allReprMethods.toPythonCopy)
    val data        = displayData.bracketAccess(0).as[List[(String, String)]].toMap
    val metadata    = displayData.bracketAccess(1).as[List[(String, String)]].toMap

    (data, metadata)
  }

  private val mimetypes = Map(
    "svg"        -> "image/svg+xml",
    "png"        -> "image/png",
    "jpeg"       -> "image/jpeg",
    "html"       -> "text/html",
    "javascript" -> "application/javascript",
    "markdown"   -> "text/markdown",
    "latex"      -> "text/latex"
  )

  private lazy val allReprMethods: Seq[(String, String)] =
    mimetypes.map { case (k, v) => s"_repr_${k}_" -> v }.toSeq
}
