// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/MalformedDirectiveError.scala

package almond.directivehandler

final class MalformedDirectiveError(message: String, positions: Seq[Position])
    extends DirectiveException(message, positions)
