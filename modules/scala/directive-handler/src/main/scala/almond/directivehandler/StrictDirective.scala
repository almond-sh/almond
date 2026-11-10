// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/StrictDirective.scala

package almond.directivehandler

import almond.directivehandler.parser.DirectiveValue

/** A directive with a key and a sequence of values.
  *
  * @param startColumn
  *   the column where the key of the directive starts
  * @param startLine
  *   the line where the key of the directive starts
  */
final case class StrictDirective(
  key: String,
  values: Seq[DirectiveValue],
  startColumn: Int = 0,
  startLine: Int = 0
) {
  private def validValues: Seq[DirectiveValue] =
    values.filter {
      case _: DirectiveValue.EmptyVal => false
      case _                          => true
    }
  override def toString: String = {
    val suffix =
      if (validValues.isEmpty) ""
      else validValues.map(" " + _.rawText).mkString
    s"//> using $key$suffix"
  }
  def stringValuesCount: Int =
    values.count {
      case _: DirectiveValue.StringVal => true
      case _                           => false
    }
}
