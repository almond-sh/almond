package almond.directives

import almond.directivehandler.{DirectiveException, Position}

final class MalformedDependencyException(message: String, positions: Seq[Position])
    extends DirectiveException(message, positions)
