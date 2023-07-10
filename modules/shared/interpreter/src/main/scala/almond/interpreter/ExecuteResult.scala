package almond.interpreter

import almond.interpreter.api.DisplayData

sealed abstract class ExecuteResult(val success: Boolean) extends Product with Serializable {

  def asSuccess: Option[ExecuteResult.Success] =
    this match {
      case s: ExecuteResult.Success => Some(s)
      case _                        => None
    }

  def asError: Option[ExecuteResult.Error] =
    this match {
      case err: ExecuteResult.Error => Some(err)
      case _                        => None
    }
}

object ExecuteResult {

  /** [[ExecuteResult]], if execution was successful.
    *
    * @param data:
    *   output data for the code that was run
    */
  final case class Success(data: DisplayData = DisplayData.empty)
      extends ExecuteResult(success = true)

  /** [[ExecuteResult]], if execution failed.
    *
    * @param name
    * @param message
    * @param stackTrace
    */
  final case class Error(
    name: String,
    message: String,
    stackTrace: List[String]
  ) extends ExecuteResult(success = false)

  object Error {
    def apply(msg: String): Error =
      Error("", msg, Nil)

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

    def exceptionToStackTraceLines(
      ex: Throwable,
      error: fansi.Attrs,
      highlightError: fansi.Attrs,
      source: fansi.Attrs
    ): Seq[String] = {
      val cutoff = Set("$main", "evaluatorRunPrinter")
      val traces = unapplySeq(ex).get.map(exception =>
        Seq(error(exception.toString).render) ++
          exception
            .getStackTrace
            .takeWhile(x => !cutoff(x.getMethodName))
            .map(highlightFrame(_, highlightError, source))
            .map(_.render)
            .toSeq
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
        msg + exOpt.fold("")(_.getMessage),
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

  /** [[ExecuteResult]], if execution was aborted.
    */
  case object Abort extends ExecuteResult(success = false)

  /** [[ExecuteResult]], if execution was exited
    */
  case object Exit extends ExecuteResult(success = true)

  /** Special [[ExecuteResult]], to exit the current message processing (used by the two step
    * launcher)
    */
  case object Close extends ExecuteResult(success = false)
}
