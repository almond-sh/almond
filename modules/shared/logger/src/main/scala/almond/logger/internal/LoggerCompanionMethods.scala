package almond.logger.internal

import java.io.PrintStream
import annotation.experimental

import almond.logger._

@experimental
trait LoggerCompanionMethods {

  def nop: Logger =
    Logger(NopLogger)

  def printStream(level: Level, out: PrintStream, colored: Boolean): Logger =
    Logger(new PrintStreamLogger(level, out, colored))

  def printStream(level: Level, out: PrintStream): Logger =
    printStream(level, out, colored = true)

  def stderr(level: Level): Logger =
    printStream(level, System.err)

}
