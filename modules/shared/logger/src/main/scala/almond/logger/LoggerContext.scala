package almond.logger

import java.io.PrintStream

import almond.logger.internal.LoggerContextImpl

trait LoggerContext {
  def apply(prefix: String): Logger

  final def apply(clazz: Class[_]): Logger =
    apply(Console.BOLD + clazz.getSimpleName.stripSuffix("$") + Console.RESET + " ")
}

object LoggerContext {

  def nop: LoggerContext =
    LoggerContextImpl(Logger.nop)

  def printStream(level: Level, out: PrintStream, colored: Boolean): LoggerContext =
    LoggerContextImpl(Logger.printStream(level, out, colored = colored))

  def printStream(level: Level, out: PrintStream): LoggerContext =
    LoggerContextImpl(Logger.printStream(level, out))

  def stderr(level: Level, colored: Boolean): LoggerContext =
    LoggerContextImpl(Logger.stderr(level, colored))

  def stderr(level: Level): LoggerContext =
    stderr(level, colored = true)

}
