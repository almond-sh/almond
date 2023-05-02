package almond.logger

import almond.logger.internal._

import scala.language.experimental.macros
import scala.quoted._

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

  inline def error(inline message: String): Unit =
    ${ Logger.errorImpl('underlying, 'message, 'null) }
  inline def error(inline message: String, inline throwable: Throwable): Unit =
    ${ Logger.errorImpl('underlying, 'message, 'throwable) }
  inline def warn(inline message: String): Unit =
    ${ Logger.warnImpl('underlying, 'message, 'null) }
  inline def warn(inline message: String, inline throwable: Throwable): Unit =
    ${ Logger.warnImpl('underlying, 'message, 'throwable) }
  inline def info(inline message: String): Unit =
    ${ Logger.infoImpl('underlying, 'message, 'null) }
  inline def info(inline message: String, inline throwable: Throwable): Unit =
    ${ Logger.infoImpl('underlying, 'message, 'throwable) }
  inline def debug(inline message: String): Unit =
    ${ Logger.debugImpl('underlying, 'message, 'null) }
  inline def debug(inline message: String, inline throwable: Throwable): Unit =
    ${ Logger.debugImpl('underlying, 'message, 'throwable) }

}

object Logger extends LoggerCompanionMethods {

  def errorImpl(
    actualLogger: Expr[ActualLogger],
    message: Expr[String],
    throwable: Expr[Throwable]
  )(using Quotes): Expr[Unit] =
    '{ if ($actualLogger.errorEnabled) $actualLogger.error($message, $throwable) }

  def warnImpl(
    actualLogger: Expr[ActualLogger],
    message: Expr[String],
    throwable: Expr[Throwable]
  )(using Quotes): Expr[Unit] =
    '{
      if ($actualLogger.warningEnabled)
        $actualLogger.warn($message, $throwable)
    }

  def infoImpl(
    actualLogger: Expr[ActualLogger],
    message: Expr[String],
    throwable: Expr[Throwable]
  )(using Quotes): Expr[Unit] =
    '{
      if ($actualLogger.infoEnabled)
        $actualLogger.info($message, $throwable)
    }

  def debugImpl(
    actualLogger: Expr[ActualLogger],
    message: Expr[String],
    throwable: Expr[Throwable]
  )(using Quotes): Expr[Unit] =
    '{
      if ($actualLogger.debugEnabled)
        $actualLogger.debug($message, $throwable)
    }

}
