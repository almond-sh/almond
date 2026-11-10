// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/Parser.scala

package almond.directivehandler.parser

import scala.annotation.tailrec
import scala.collection.mutable

/** Recursive-descent parser that consumes a token stream and produces [[UsingDirective]] nodes.
  *
  * Grammar:
  * {{{
  * Directives ::= { Directive }
  * Directive  ::= "using" Key Values
  * Key        ::= Ident                  (dotted keys are a single Ident, e.g. "test.dep")
  * Values     ::= { [Comma] Value }
  * Value      ::= StringLit | BoolLit | Ident
  * }}}
  *
  * When Values is empty (key immediately followed by Newline), a single [[DirectiveValue.EmptyVal]]
  * is produced.
  *
  * Error recovery: on unexpected token, a diagnostic is emitted and the parser skips to the next
  * Newline token.
  */
object Parser {

  def parse(tokens: Seq[Token]): (Seq[UsingDirective], Seq[UsingDirectiveDiagnostic]) = {
    val diagnostics = mutable.ArrayBuffer.empty[UsingDirectiveDiagnostic]
    val directives  = mutable.ArrayBuffer.empty[UsingDirective]
    val arr         = tokens.toIndexedSeq
    var pos         = 0

    def current: Token =
      if (pos < arr.length) arr(pos) else Token.Eof(Position(0, 0, 0))

    def advance(): Unit =
      if (pos < arr.length) pos += 1

    def skipToNewline(): Unit = {
      @tailrec
      def skipUntilBoundary(): Unit =
        current match {
          case _: Token.Newline | _: Token.Eof => ()
          case _ =>
            advance()
            skipUntilBoundary()
        }
      skipUntilBoundary()
      current match {
        case _: Token.Newline => advance()
        case _                => ()
      }
    }

    def error(msg: String, p: Position): Unit =
      diagnostics += UsingDirectiveDiagnostic(msg, DiagnosticSeverity.Error, p)

    def warn(msg: String, p: Position): Unit =
      diagnostics += UsingDirectiveDiagnostic(msg, DiagnosticSeverity.Warning, p)

    def parseValues(): Seq[DirectiveValue] = {
      val values = mutable.ArrayBuffer.empty[DirectiveValue]

      @tailrec
      def loop(): Seq[DirectiveValue] =
        current match {
          case _: Token.Newline | _: Token.Eof =>
            values.toList
          case Token.Comma(p) =>
            if (values.isEmpty)
              values += DirectiveValue.StringVal(",", isQuoted = false, p)
            else
              warn(
                "Use of commas as separators is deprecated. Only whitespace is necessary.",
                p
              )
            advance()
            loop()
          case Token.StringLit(v, p) =>
            values += DirectiveValue.StringVal(v, isQuoted = true, p)
            advance()
            loop()
          case Token.BoolLit(v, p) =>
            values += DirectiveValue.BoolVal(v, p)
            advance()
            loop()
          case Token.Ident(v, p) =>
            if (v.endsWith(","))
              warn(
                s"Value '$v' ends with a comma - this is likely a typo from a double-comma sequence.",
                p
              )
            values += DirectiveValue.StringVal(v, isQuoted = false, p)
            advance()
            loop()
          case Token.Using(p) =>
            values += DirectiveValue.StringVal("using", isQuoted = false, p)
            advance()
            loop()
          case Token.LexError(msg, p) =>
            error(s"Lexer error: $msg", p)
            advance()
            loop()
        }

      loop()
    }

    @tailrec
    def parseDirectives(): Unit =
      current match {
        case _: Token.Eof =>
          ()
        case Token.Newline(_) =>
          advance()
          parseDirectives()
        case Token.Using(usingPos) =>
          advance()
          current match {
            case Token.Ident(keyText, keyPos) =>
              advance()
              val values = parseValues()
              val finalValues =
                if (values.isEmpty)
                  Seq(DirectiveValue.EmptyVal(
                    Position(
                      keyPos.line,
                      keyPos.column + keyText.length,
                      keyPos.offset + keyText.length
                    )
                  ))
                else values
              directives += UsingDirective(keyText, finalValues, keyPos)
              current match {
                case _: Token.Newline => advance()
                case _                => ()
              }

            case Token.Newline(_) =>
              error("Expected a key after `using`", usingPos)
              advance()

            case _: Token.Eof =>
              error("Expected a key after `using`", usingPos)

            case t =>
              error(s"Expected a key after `using`, found: ${t.productPrefix}", t.pos)
              skipToNewline()
          }
          parseDirectives()

        case Token.LexError(msg, p) =>
          error(s"Lexer error: $msg", p)
          skipToNewline()
          parseDirectives()

        case t =>
          error(s"Unexpected token: ${t.productPrefix}", t.pos)
          skipToNewline()
          parseDirectives()
      }

    parseDirectives()
    (directives.toList, diagnostics.toList)
  }
}
