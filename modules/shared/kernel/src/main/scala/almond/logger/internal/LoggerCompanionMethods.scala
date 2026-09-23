package almond.logger.internal

import java.io.PrintStream

import almond.logger._

trait LoggerCompanionMethods {

  def nop: Logger =
    Logger(NopLogger)

  def printStream(level: Level, out: PrintStream, colored: Boolean, addPid: Boolean): Logger =
    Logger(new PrintStreamLogger(level, out, colored, addPid))

  def printStream(level: Level, out: PrintStream, colored: Boolean): Logger =
    printStream(level, out, colored, addPid = false)

  def printStream(level: Level, out: PrintStream): Logger =
    printStream(level, out, colored = true)

  def stderr(level: Level, colored: Boolean, addPid: Boolean): Logger =
    printStream(level, System.err, colored, addPid)

  def stderr(level: Level, colored: Boolean): Logger =
    stderr(level, colored, addPid = false)

  def stderr(level: Level): Logger =
    stderr(level, colored = true, addPid = false)

}
