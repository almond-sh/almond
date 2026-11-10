// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/Position.scala
// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/DirectiveValue.scala
// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/UsingDirective.scala
// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/Diagnostics.scala
// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/CommentExtractor.scala
// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/Token.scala

package almond.directivehandler.parser

// The scala3-directives-parser module of the Scala 3 compiler (Apache-2.0), itself coming from
// Scala CLI, is only published for Scala 3 (and depends on the Scala 3 standard library), so we
// keep a copy of it here, written in a syntax that compiles with Scala 2.12, 2.13 and 3. Its
// syntax is specified in its README.

/** Position within a source file. Lines and columns are 0-based. */
final case class Position(line: Int, column: Int, offset: Int)

sealed abstract class DirectiveValue extends Product with Serializable {

  /** Position of this value in the source file. */
  def position: Position

  /** Raw source text representation (e.g. `"hello"` with quotes for quoted strings). */
  def rawText: String = this match {
    case DirectiveValue.StringVal(v, true, _)  => "\"" + v + "\""
    case DirectiveValue.StringVal(v, false, _) => v
    case DirectiveValue.BoolVal(v, _)          => v.toString
    case DirectiveValue.EmptyVal(_)            => ""
  }

  /** The string content of the value (without quotes), or boolean/empty as string. */
  def stringValue: String = this match {
    case DirectiveValue.StringVal(v, _, _) => v
    case DirectiveValue.BoolVal(v, _)      => v.toString
    case DirectiveValue.EmptyVal(_)        => ""
  }

  /** Whether this value is a quoted string literal. */
  def isQuotedString: Boolean = this match {
    case DirectiveValue.StringVal(_, true, _) => true
    case _                                    => false
  }
}

object DirectiveValue {

  /** A string value, either quoted (`"hello"`) or bare (`hello`). */
  final case class StringVal(value: String, isQuoted: Boolean, position: Position)
      extends DirectiveValue

  /** A boolean literal (`true` or `false`). */
  final case class BoolVal(value: Boolean, position: Position) extends DirectiveValue

  /** No value was provided after the key (treated as `true` downstream). */
  final case class EmptyVal(position: Position) extends DirectiveValue
}

/** A single parsed `//> using` directive. */
final case class UsingDirective(
  key: String,
  values: Seq[DirectiveValue],
  keyPosition: Position
)

sealed abstract class DiagnosticSeverity extends Product with Serializable

object DiagnosticSeverity {
  case object Error   extends DiagnosticSeverity
  case object Warning extends DiagnosticSeverity
}

final case class UsingDirectiveDiagnostic(
  message: String,
  severity: DiagnosticSeverity,
  position: Position
)

/** The result of parsing a source file for using directives.
  *
  * @param codeOffset
  *   offset in the source where code starts (or its length if it only has directives / comments)
  */
final case class UsingDirectivesResult(
  directives: Seq[UsingDirective],
  codeOffset: Int,
  diagnostics: Seq[UsingDirectiveDiagnostic]
)

/** Represents a single `//> using ...` directive line extracted from a source file.
  *
  * @param content
  *   the full text of the line, including the `//> ` prefix (no trailing newline)
  * @param lineNum
  *   0-indexed line number in the original source file
  * @param lineStartOffset
  *   absolute offset of the first character of this line in the source file
  */
final case class DirectiveLine(
  content: String,
  lineNum: Int,
  lineStartOffset: Int
)

/** Result of the comment extraction phase. */
final case class ExtractorResult(
  directiveLines: Seq[DirectiveLine],
  codeOffset: Int,
  diagnostics: Seq[UsingDirectiveDiagnostic]
)

sealed abstract class Token extends Product with Serializable {

  /** Absolute position of this token in the source file. */
  def pos: Position
}

object Token {

  /** The `using` keyword. */
  final case class Using(pos: Position) extends Token

  /** A bare identifier or value (non-quoted, non-whitespace sequence). */
  final case class Ident(value: String, pos: Position) extends Token

  /** A double-quoted string literal. */
  final case class StringLit(value: String, pos: Position) extends Token

  /** Boolean literal `true` or `false`. */
  final case class BoolLit(value: Boolean, pos: Position) extends Token

  /** Comma - accepted as a deprecated value separator. */
  final case class Comma(pos: Position) extends Token

  /** End of a directive line. */
  final case class Newline(pos: Position) extends Token

  /** End of token stream. */
  final case class Eof(pos: Position) extends Token

  /** A lexer error. */
  final case class LexError(message: String, pos: Position) extends Token
}
