package almond.logger

import java.io.PrintStream

import almond.logger.internal.LoggerContextImpl

trait LoggerContext {
  def apply(prefix: String): Logger

  def colored: Boolean

  final def apply(clazz: Class[_]): Logger =
    apply(
      if (colored)
        Console.BOLD + clazz.getSimpleName.stripSuffix("$") + Console.RESET + " "
      else
        clazz.getSimpleName.stripSuffix("$") + " "
    )
}

object LoggerContext {

  def nop: LoggerContext =
    LoggerContextImpl(Logger.nop, colored = false)

  def printStream(
    level: Level,
    out: PrintStream,
    colored: Boolean,
    addPid: Boolean
  ): LoggerContext =
    LoggerContextImpl(
      Logger.printStream(level, out, colored = colored, addPid = addPid),
      colored = colored
    )

  def printStream(level: Level, out: PrintStream, colored: Boolean): LoggerContext =
    LoggerContextImpl(
      Logger.printStream(level, out, colored = colored),
      colored = colored
    )

  def printStream(level: Level, out: PrintStream): LoggerContext =
    LoggerContextImpl(
      Logger.printStream(level, out),
      colored = true
    )

  def stderr(level: Level, colored: Boolean, addPid: Boolean): LoggerContext =
    LoggerContextImpl(
      Logger.stderr(level, colored, addPid),
      colored = colored
    )

  def stderr(level: Level, colored: Boolean): LoggerContext =
    stderr(level, colored, addPid = false)

  def stderr(level: Level): LoggerContext =
    stderr(level, colored = true, addPid = false)

}
