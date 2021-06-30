package almond

import java.{util => ju}
import jupyter.{Displayer, Displayers}
import me.shadaj.scalapy.interpreter.CPythonInterpreter
import me.shadaj.scalapy.py
import me.shadaj.scalapy.py.{PyQuote, SeqConverters}
import scala.jdk.CollectionConverters._

package object scalapy {
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

  private def formatDisplayData(obj: py.Any): (Map[String, String], Map[String, String]) = {
    CPythonInterpreter.execManyLines(formatDisplayDataPy)

    val displayData = py.Dynamic.global.format_display_data(obj, allReprMethods.toPythonCopy)
    val data = displayData.bracketAccess(0).as[List[(String, String)]].toMap
    val metadata = displayData.bracketAccess(1).as[List[(String, String)]].toMap

    (data, metadata)
  }

  private val mimetypes = Map(
    "svg" -> "image/svg+xml",
    "png" -> "image/png",
    "jpeg" -> "image/jpeg",
    "html" -> "text/html",
    "javascript" -> "application/javascript",
    "markdown" -> "text/markdown",
    "latex" -> "text/latex"
  )

  private lazy val allReprMethods: Seq[(String, String)] =
    mimetypes.map { case (k, v) => s"_repr_${k}_" -> v }.toSeq

  private val formatDisplayDataPy: String =
    """import json
      |def format_display_data(obj, include):
      |    repr_methods = ((t, m) for m, t in include if m in set(dir(obj)))
      |    representations = ((t, getattr(obj, m)()) for t, m in repr_methods)
      |    display_data = (
      |        (t, (r[0], r[1]) if isinstance(r, tuple) and len(r) == 2 else (r, None))
      |        for t, r in representations if r is not None
      |    )
      |    display_data = [(t, m, md) for t, (m, md) in display_data if m is not None]
      |    data = [
      |        (t, d if isinstance(d, str) else json.dumps(d))
      |        for t, d, _ in display_data
      |    ]
      |    metadata = [
      |        (t, md if isinstance(md, str) else json.dumps(md))
      |        for t, _, md in display_data if md is not None
      |    ]
      |    return data, metadata
    """.stripMargin
}
