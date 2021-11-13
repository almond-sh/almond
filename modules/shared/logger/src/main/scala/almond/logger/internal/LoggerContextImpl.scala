package almond.logger.internal
import annotation.experimental
import almond.logger.{Logger, LoggerContext}

@experimental
final case class LoggerContextImpl(baseLogger: Logger) extends LoggerContext {
  def apply(prefix: String): Logger =
    baseLogger.prefix(prefix)
}
