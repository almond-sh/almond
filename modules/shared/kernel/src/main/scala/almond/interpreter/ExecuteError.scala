package almond.interpreter

import almond.interpreter.api.ExecuteResult
import almond.logger.internal.PrintStreamLogger

object ExecuteError {

  // these come from Ammonite
  // exception display was tweaked a bit (too much red for notebooks else)

  def highlightFrame(
    f: StackTraceElement,
    highlightError: fansi.Attrs,
    source: fansi.Attrs
  ) = {
    val src =
      if (f.isNativeMethod) source("Native Method")
      else if (f.getFileName == null) source("Unknown Source")
      else source(f.getFileName) ++ ":" ++ source(f.getLineNumber.toString)

    val prefix :+ clsName = f.getClassName.split('.').toSeq
    val prefixString      = prefix.map(_ + '.').mkString("")
    val clsNameString     = clsName // .replace("$", error("$"))
    val method =
      fansi.Str(prefixString) ++ highlightError(clsNameString) ++ "." ++
        highlightError(f.getMethodName)

    fansi.Str(s"  ") ++ method ++ "(" ++ src ++ ")"
  }

  private def unapplySeq(t: Throwable): Option[Seq[Throwable]] = {
    def rec(t: Throwable): List[Throwable] =
      t match {
        case null => Nil
        case t    => t :: rec(t.getCause)
      }
    Some(rec(t))
  }

  private def exceptionToStackTraceLines(
    ex: Throwable,
    error: fansi.Attrs,
    highlightError: fansi.Attrs,
    source: fansi.Attrs
  ): Seq[String] = {
    val cutoff = Set("$main", "evaluatorRunPrinter")
    val traces = unapplySeq(ex).get.map(exception =>
      Seq(error(PrintStreamLogger.exceptionString(exception)).render) ++ {
        PrintStreamLogger.exceptionStackTrace(exception) match {
          case Left(err) =>
            Seq(s"  $err")
          case Right(stackTrace) =>
            stackTrace
              .takeWhile(x => !cutoff(x.getMethodName))
              .map(highlightFrame(_, highlightError, source))
              .map(_.render)
              .toSeq
        }
      }
    )
    traces.flatten
  }

  def showException(
    ex: Throwable,
    error: fansi.Attrs,
    highlightError: fansi.Attrs,
    source: fansi.Attrs
  ) =
    exceptionToStackTraceLines(ex, error, highlightError, source).mkString(System.lineSeparator())

  def error(
    errorColor: fansi.Attrs,
    literalColor: fansi.Attrs,
    exOpt: Option[Throwable],
    msg: String
  ) =
    ExecuteResult.Error(
      exOpt.fold("")(_.getClass.getName),
      msg + exOpt.fold("")(PrintStreamLogger.exceptionMessage),
      exOpt.fold(List.empty[String])(ex =>
        exceptionToStackTraceLines(
          ex,
          errorColor,
          fansi.Attr.Reset,
          literalColor
        ).toList
      )
    )

}
