package almond.logger

import almond.logger.internal._

import scala.language.experimental.macros

final case class Logger(underlying: ActualLogger) {

  def prefix(prefix: String): Logger =
    Logger(underlying.prefix(prefix))

  def error(message: String): Unit = macro LoggerMacros.error
  def error(message: String, throwable: Throwable): Unit = macro LoggerMacros.errorEx
  def warn(message: String): Unit = macro LoggerMacros.warn
  def warn(message: String, throwable: Throwable): Unit = macro LoggerMacros.warnEx
  def info(message: String): Unit = macro LoggerMacros.info
  def info(message: String, throwable: Throwable): Unit = macro LoggerMacros.infoEx
  def debug(message: String): Unit = macro LoggerMacros.debug
  def debug(message: String, throwable: Throwable): Unit = macro LoggerMacros.debugEx
}

object Logger extends LoggerCompanionMethods
