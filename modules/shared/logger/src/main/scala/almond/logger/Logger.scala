package almond.logger

import java.io.PrintStream

import almond.logger.internal.{ActualLogger, PrintStreamLogger, LoggerMacros, NopLogger}

import scala.language.experimental.macros

final case class Logger private (underlying: ActualLogger) {
  def error(message: String): Unit = macro LoggerMacros.error
  def error(message: String, throwable: Throwable): Unit = macro LoggerMacros.errorEx
  def warn(message: String): Unit = macro LoggerMacros.warn
  def warn(message: String, throwable: Throwable): Unit = macro LoggerMacros.warnEx
  def info(message: String): Unit = macro LoggerMacros.info
  def info(message: String, throwable: Throwable): Unit = macro LoggerMacros.infoEx
  def debug(message: String): Unit = macro LoggerMacros.debug
  def debug(message: String, throwable: Throwable): Unit = macro LoggerMacros.debugEx

  def prefix(prefix: String): Logger =
    Logger(underlying.prefix(prefix))
}

object Logger {

  def nop: Logger =
    Logger(NopLogger)

  def printStream(level: Level, out: PrintStream): Logger =
    Logger(new PrintStreamLogger(level, out))

  def stderr(level: Level): Logger =
    printStream(level, System.err)

}
