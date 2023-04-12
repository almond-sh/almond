package almond.toree

import almond.api.JupyterApi
import ammonite.util.Ref

import java.util.regex.Pattern
import java.util.Locale

import scala.collection.mutable

object LineMagicHook {

  private val sep = Pattern.compile("\\s+")

  def inspect(code: String): Iterator[Either[(Seq[String], String, String), String]] = {
    var parsingMagics = true
    code.linesWithSeparators.zip(code.linesIterator).map {
      case (rawLine, line) =>
        if (parsingMagics && line.startsWith("%") && !line.drop(1).startsWith("%"))
          Left((sep.split(line).toSeq, rawLine, line))
        else {
          if (parsingMagics) {
            val trimmed = line.trim()
            parsingMagics = trimmed.isEmpty || trimmed.startsWith("//")
          }
          Right(rawLine)
        }
    }
  }

  def hook(pprinter: Ref[pprint.PPrinter]): JupyterApi.ExecuteHook = {

    val handlers = LineMagicHandlers.handlers(pprinter)

    code =>

      val magicsIt      = inspect(code)
      var errorOpt      = Option.empty[JupyterApi.ExecuteHookResult]
      val remainingCode = new StringBuilder
      while (magicsIt.hasNext && errorOpt.isEmpty)
        magicsIt.next() match {
          case Left((elems, rawLine, line)) =>
            assert(elems.nonEmpty)

            val name   = elems.head
            val values = elems.tail

            assert(name.startsWith("%"))

            handlers.get(name.toLowerCase(Locale.ROOT).stripPrefix("%")) match {
              case None =>
                System.err.println(s"Warning: ignoring unrecognized Toree line magic $name")
              case Some(handler) =>
                handler.handle(name, values) match {
                  case Left(res) => errorOpt = Some(res)
                  case Right(substituteCode) =>
                    remainingCode ++= substituteCode
                    remainingCode ++= rawLine.substring(line.length)
                }
            }

          case Right(code) =>
            remainingCode ++= code
        }

      errorOpt.toLeft(remainingCode.result())
  }

}
