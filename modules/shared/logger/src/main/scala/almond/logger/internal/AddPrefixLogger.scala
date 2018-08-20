package almond.logger.internal

import almond.logger.Level

final case class AddPrefixLogger(
  underlying: ActualLogger,
  prefix: String
) extends ActualLogger {

  def level: Level =
    underlying.level

  def log(level: Level, message: String, exception: Throwable): Unit =
    underlying.log(level, prefix + message, exception)
}
