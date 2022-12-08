package almond.logger

import java.io.PrintStream
import annotation.experimental

import almond.logger.internal.LoggerContextImpl

@experimental
trait LoggerContext {
  def apply(prefix: String): Logger

  final def apply(clazz: Class[_]): Logger =
    apply(Console.BOLD + clazz.getSimpleName.stripSuffix("$") + Console.RESET + " ")
}

object LoggerContext {

  def nop: LoggerContext =
    LoggerContextImpl(Logger.nop)

  def printStream(level: Level, out: PrintStream): LoggerContext =
    LoggerContextImpl(Logger.printStream(level, out))

  def stderr(level: Level): LoggerContext =
    LoggerContextImpl(Logger.stderr(level))

}
