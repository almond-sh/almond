package almond.directives

import scala.cli.directivehandler.{DirectiveException, Position}

final class MalformedDependencyException(message: String, positions: Seq[Position])
    extends DirectiveException(message, positions)
