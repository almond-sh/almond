package almond.toree

import almond.api.JupyterApi
import ammonite.util.Ref

import java.util.regex.Pattern
import java.util.Locale

import scala.collection.mutable

object LineMagicHook {

  private val userHandlers0 = new mutable.HashMap[String, LineMagicHandler]

  def addHandler(name: String)(handler: LineMagicHandler): Unit =
    userHandlers0 += name.toLowerCase(Locale.ROOT) -> handler

  def clearHandlers(): Unit =
    userHandlers0.clear()

  def userHandlers: Map[String, LineMagicHandler] =
    userHandlers0.toMap

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

            val name0 = name.toLowerCase(Locale.ROOT).stripPrefix("%")

            userHandlers0.get(name0).orElse(handlers.get(name0)) match {
              case None =>
                // kept, for execute hooks registered after this one to handle it (like the
                // %sql one of the docs) - unrecognizedMagicsHook reports it if none does
                remainingCode ++= rawLine
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

  /** Reports the line and cell magics that no execute hook handled
    *
    * The magic hooks keep the magics they don't know about, for other execute hooks to handle them.
    * This hook should run after all the others, so that the magics that are still there by then are
    * reported as errors, rather than reaching the compiler.
    */
  val unrecognizedMagicsHook: JupyterApi.ExecuteHook = { code =>
    val cellMagicOpt = code.linesIterator.take(1).toList.collectFirst {
      case line if line.startsWith("%%") => sep.split(line.trim).head
    }
    val lineMagics = inspect(code)
      .collect {
        case Left((elems, _, _)) => elems.head
      }
      .toVector
    val messageOpt = cellMagicOpt match {
      case Some(name) => Some(s"Cell magic $name not found")
      case None if lineMagics.nonEmpty =>
        val (kind, names) =
          if (lineMagics.length == 1) ("magic", lineMagics.head)
          else ("magics", lineMagics.mkString(", "))
        Some(s"Line $kind $names not found")
      case None => None
    }
    messageOpt match {
      case Some(message) => Left(JupyterApi.ExecuteHookResult.Error("", message, Nil))
      case None          => Right(code)
    }
  }

}
