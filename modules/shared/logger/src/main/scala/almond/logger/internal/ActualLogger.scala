package almond.logger.internal

import almond.logger.Level

trait ActualLogger {

  def level: Level

  final def errorEnabled: Boolean =
    level.errorEnabled
  final def warningEnabled: Boolean =
    level.warningEnabled
  final def infoEnabled: Boolean =
    level.infoEnabled
  final def debugEnabled: Boolean =
    level.debugEnabled

  final def error(message: String, exception: Throwable = null): Unit =
    log(Level.Error, message, exception)
  final def warn(message: String, exception: Throwable = null): Unit =
    log(Level.Warning, message, exception)
  final def info(message: String, exception: Throwable = null): Unit =
    log(Level.Info, message, exception)
  final def debug(message: String, exception: Throwable = null): Unit =
    log(Level.Debug, message, exception)

  def log(level: Level, message: String, exception: Throwable = null): Unit

  final def prefix(prefix: String): ActualLogger =
    AddPrefixLogger(this, prefix)
}
