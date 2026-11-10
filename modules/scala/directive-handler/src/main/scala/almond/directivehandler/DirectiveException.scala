// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveException.scala

package almond.directivehandler

abstract class DirectiveException(
  message: String,
  val positions: Seq[Position] = Nil,
  cause: Throwable = null
) extends Exception(message, cause)
