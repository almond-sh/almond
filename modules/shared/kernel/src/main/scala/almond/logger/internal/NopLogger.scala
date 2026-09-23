package almond.logger.internal

import almond.logger.Level

object NopLogger extends ActualLogger {

  def level: Level.None.type =
    Level.None

  def log(level: Level, message: String, exception: Throwable): Unit =
    ()
}
