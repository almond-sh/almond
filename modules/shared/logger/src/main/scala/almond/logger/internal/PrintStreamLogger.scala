package almond.logger.internal

import java.io.PrintStream
import java.lang.management.ManagementFactory

import almond.logger.Level

final class PrintStreamLogger(
  val level: Level,
  out: PrintStream,
  colored: Boolean,
  addPid: Boolean
) extends ActualLogger {

  def this(level: Level, out: PrintStream) =
    this(level, out, colored = true, addPid = false)

  def log(level: Level, message: String, exception: Throwable = null): Unit = {
    val b = new StringBuilder

    if (addPid) {
      if (colored)
        b ++= Console.CYAN
      b ++= PrintStreamLogger.pid
      if (colored)
        b ++= Console.RESET
      b += ' '
    }

    b ++= (if (colored) level.coloredName else level.name)
    b += ' '
    b ++= message

    out.println(b.result())
    if (exception != null)
      exception.printStackTrace(out)
  }
}

object PrintStreamLogger {
  lazy val pid: String = ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@')
}
