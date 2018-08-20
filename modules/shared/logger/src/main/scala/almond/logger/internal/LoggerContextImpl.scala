package almond.logger.internal

import almond.logger.{Logger, LoggerContext}

final case class LoggerContextImpl(baseLogger: Logger) extends LoggerContext {
  def apply(prefix: String): Logger =
    baseLogger.prefix(prefix)
}
