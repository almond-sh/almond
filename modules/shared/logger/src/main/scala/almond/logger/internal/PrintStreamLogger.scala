package almond.logger.internal

import java.io.PrintStream

import almond.logger.Level

import scala.annotation.tailrec

final class PrintStreamLogger(
  val level: Level,
  out: PrintStream,
  colored: Boolean
) extends ActualLogger {

  def this(level: Level, out: PrintStream) =
    this(level, out, colored = true)

  def log(level: Level, message: String, exception: Throwable = null): Unit = {
    val b = new StringBuilder

    b ++= (if (colored) level.coloredName else level.name)
    b += ' '
    b ++= message

    @tailrec
    def addException(ex: Throwable): Unit =
      if (ex != null) {
        b += '\n' // FIXME Not portable
        b ++= ex.toString
        for (elem <- ex.getStackTrace) {
          b ++= "\n  " // FIXME Not portable
          b ++= elem.toString
        }
        addException(ex.getCause)
      }

    addException(exception)

    out.println(b.result())
  }
}
