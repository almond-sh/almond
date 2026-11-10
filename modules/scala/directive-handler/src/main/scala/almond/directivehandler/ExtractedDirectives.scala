// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/ExtractedDirectives.scala

package almond.directivehandler

import almond.directivehandler.parser.{DiagnosticSeverity, DirectiveValue, UsingDirectivesParser}

final case class ExtractedDirectives(
  directives: Seq[StrictDirective],
  positions: Option[Position.File]
) {
  def ++(other: ExtractedDirectives): ExtractedDirectives =
    ExtractedDirectives(directives ++ other.directives, positions)
}

object ExtractedDirectives {

  def empty: ExtractedDirectives = ExtractedDirectives(Seq.empty, None)

  def from(
    contentChars: IndexedSeq[Char],
    path: Either[String, os.Path],
    maybeRecoverOnError: DirectiveException => Option[DirectiveException] = Some(_)
  ): Either[DirectiveException, ExtractedDirectives] = {
    val result = UsingDirectivesParser.parse(contentChars)

    // warnings (deprecated syntax, directives after code) are ignored for now
    val malformedDirectiveErrors = result.diagnostics
      .filter(_.severity == DiagnosticSeverity.Error)
      .map { diag =>
        val coords = (diag.position.line, diag.position.column)
        new MalformedDirectiveError(diag.message, Seq(Position.File(path, coords, coords)))
      }

    val maybeCompositeMalformedDirectiveError =
      if (malformedDirectiveErrors.nonEmpty)
        maybeRecoverOnError(CompositeDirectiveException(malformedDirectiveErrors))
      else None

    if (malformedDirectiveErrors.isEmpty || maybeCompositeMalformedDirectiveError.isEmpty) {
      val directives = result.directives

      val directivesPositionOpt =
        if (directives.isEmpty || directives.forall(_.key.startsWith("target."))) None
        else {
          val lastDirective = directives.last
          val (endLine, endCol) = lastDirective.values.lastOption match {
            case Some(sv: DirectiveValue.StringVal) =>
              (sv.position.line, sv.position.column + sv.rawText.length)
            case Some(other) =>
              (other.position.line, other.position.column + other.rawText.length)
            case None =>
              val kp = lastDirective.keyPosition
              (kp.line, kp.column + lastDirective.key.length)
          }
          Some(Position.File(path, (0, 0), (endLine, endCol)))
        }

      val strictDirectives = directives.map { dir =>
        StrictDirective(dir.key, dir.values, dir.keyPosition.column, dir.keyPosition.line)
      }

      Right(ExtractedDirectives(strictDirectives, directivesPositionOpt))
    }
    else
      maybeCompositeMalformedDirectiveError match {
        case Some(e) => Left(e)
        case None    => Right(ExtractedDirectives.empty)
      }
  }

}
