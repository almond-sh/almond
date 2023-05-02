package almond.logger.internal

import scala.reflect.macros.blackbox

object LoggerMacros {

  def error(c: blackbox.Context)(message: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"if (${c.prefix}.underlying.errorEnabled) ${c.prefix}.underlying.error($message)"
  }

  def warn(c: blackbox.Context)(message: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"if (${c.prefix}.underlying.warningEnabled) ${c.prefix}.underlying.warn($message)"
  }

  def info(c: blackbox.Context)(message: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"if (${c.prefix}.underlying.infoEnabled) ${c.prefix}.underlying.info($message)"
  }

  def debug(c: blackbox.Context)(message: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"if (${c.prefix}.underlying.debugEnabled) ${c.prefix}.underlying.debug($message)"
  }

  def errorEx(c: blackbox.Context)(
    message: c.Expr[String],
    throwable: c.Expr[Throwable]
  ): c.universe.Tree = {
    import c.universe._
    q"if (${c.prefix}.underlying.errorEnabled) ${c.prefix}.underlying.error($message, $throwable)"
  }

  def warnEx(c: blackbox.Context)(
    message: c.Expr[String],
    throwable: c.Expr[Throwable]
  ): c.universe.Tree = {
    import c.universe._
    q"if (${c.prefix}.underlying.warningEnabled) ${c.prefix}.underlying.warn($message, $throwable)"
  }

  def infoEx(c: blackbox.Context)(
    message: c.Expr[String],
    throwable: c.Expr[Throwable]
  ): c.universe.Tree = {
    import c.universe._
    q"if (${c.prefix}.underlying.infoEnabled) ${c.prefix}.underlying.info($message, $throwable)"
  }

  def debugEx(c: blackbox.Context)(
    message: c.Expr[String],
    throwable: c.Expr[Throwable]
  ): c.universe.Tree = {
    import c.universe._
    q"if (${c.prefix}.underlying.debugEnabled) ${c.prefix}.underlying.debug($message, $throwable)"
  }

}
