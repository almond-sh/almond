package almond.logger.internal

import java.io.PrintStream
import java.lang.management.ManagementFactory

import scala.util.control.NonFatal

import almond.logger.Level

final class PrintStreamLogger(
  val level: Level,
  out: PrintStream,
  colored: Boolean,
  addPid: Boolean
) extends ActualLogger {

  import PrintStreamLogger._

  def this(level: Level, out: PrintStream) =
    this(level, out, colored = true, addPid = false)

  def log(level: Level, message: String, exception: Throwable = null): Unit = {
    val b = new StringBuilder

    if (addPid) {
      if (colored)
        b ++= Console.CYAN
      b ++= pid
      if (colored)
        b ++= Console.RESET
      b += ' '
    }

    b ++= (if (colored) level.coloredName else level.name)
    b += ' '
    b ++= message

    out.println(b.result())
    if (exception != null)
      try exception.printStackTrace(out)
      catch {
        case NonFatal(_) =>
          val message         = exceptionString(exception)
          val maybeStackTrace = exceptionStackTrace(exception)
          out.println(message)
          maybeStackTrace match {
            case Left(err) =>
              out.println(s"  $err")
            case Right(stackTrace) =>
              for (elem <- stackTrace)
                out.println(s"  $elem")
          }
      }
  }
}

object PrintStreamLogger {
  lazy val pid: String = ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@')

  def exceptionString(ex: Throwable): String =
    try ex.toString
    catch {
      case NonFatal(_) =>
        ex.getClass.getName
    }
  def exceptionMessage(ex: Throwable): String =
    try ex.getMessage
    catch {
      case NonFatal(ex) =>
        s"[no message: caught ${exceptionString(ex)}]"
    }
  def exceptionStackTrace(ex: Throwable): Either[String, Array[StackTraceElement]] =
    try Option(ex.getStackTrace).toRight("null stack trace")
    catch {
      case NonFatal(ex0) =>
        Left(s"Caught ${exceptionString(ex0)} while trying to get stack trace")
    }
}
