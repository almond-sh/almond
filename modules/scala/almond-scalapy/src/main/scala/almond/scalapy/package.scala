package almond

import java.{util => ju}
import jupyter.{Displayer, Displayers}
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
    val availableReprMethods =
      py"((t, m) for m, t in ${allReprMethods.toPythonCopy} if m in set(dir($obj)))"
    val representations =
      py"((t, getattr($obj, m)()) for t, m in ${availableReprMethods})"
    val extractDataMetadata =
      py"lambda r: (r[0], r[1]) if isinstance(r, tuple) and len(r) == 2 else (r, None)"
    val displayData =
      py"((t, ${extractDataMetadata}(r)) for t, r in ${representations} if r is not None)"
    val displayDataNotNull =
      py"[(t, m, md) for t, (m, md) in ${displayData} if m is not None]"

    val pyJson = py.module("json")
    val dataJson =
      py"[(t, ${pyJson}.dumps(d)) for t, d, _ in ${displayDataNotNull}]"
        .as[List[(String, String)]]
        .toMap
    val metadataJson =
      py"[(t, ${pyJson}.dumps(md)) for t, _, md in ${displayDataNotNull} if md is not None]"
        .as[List[(String, String)]]
        .toMap

    (dataJson, metadataJson)
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
}
