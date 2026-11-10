// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveUtil.scala

package almond.directivehandler

import almond.directivehandler.parser.DirectiveValue
import dependency.AnyDependency
import dependency.parser.DependencyParser

import almond.directivehandler.EitherSequence._

object DirectiveUtil {

  def position(
    v: DirectiveValue,
    path: Either[String, os.Path],
    skipQuotes: Boolean = false
  ): Position.File = {
    val line       = v.position.line
    val column     = v.position.column + (if (skipQuotes) 1 else 0)
    val endLinePos = column + v.stringValue.length
    Position.File(path, (line, column), (line, endLinePos))
  }

  def concatAllValues(
    scopedDirective: ScopedDirective
  ): Seq[Positioned[String]] =
    scopedDirective.directive.values.map { v =>
      val pos = position(v, scopedDirective.maybePath, skipQuotes = v.isQuotedString)
      Positioned(pos, v.stringValue)
    }

  implicit class PositionedDepsOps(private val deps: List[Positioned[String]]) extends AnyVal {
    def asDependencies: Either[DirectiveException, Seq[Positioned[AnyDependency]]] =
      deps
        .map {
          _.map { str =>
            DependencyParser.parse(str).left.map(new DependencyFormatError(str, _))
          }.eitherSequence
        }
        .sequence
        .left.map(CompositeDirectiveException(_))
  }
}
