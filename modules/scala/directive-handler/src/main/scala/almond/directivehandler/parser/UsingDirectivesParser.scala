// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/UsingDirectivesParser.scala

package almond.directivehandler.parser

import scala.collection.mutable

/** Public entry point for the using directives parser.
  *
  * The pipeline is:
  *   1. [[CommentExtractor.extract]] - scans the source and identifies `//> using` lines
  *   2. [[Lexer.tokenize]] - tokenizes the content of each directive line
  *   3. [[Parser.parse]] - converts the token stream into [[UsingDirective]] nodes
  */
object UsingDirectivesParser {

  /** Parse a source file and return all using directives along with diagnostics.
    *
    * @param content
    *   the raw content of the source file as a character sequence
    * @return
    *   a [[UsingDirectivesResult]] with parsed directives, code offset, and diagnostics
    */
  def parse(content: IndexedSeq[Char]): UsingDirectivesResult = {
    val extracted = CommentExtractor.extract(content)

    val allTokens = mutable.ArrayBuffer.empty[Token]
    for (line <- extracted.directiveLines)
      allTokens ++= Lexer.tokenize(line.content, line.lineNum, line.lineStartOffset)

    allTokens += Token.Eof(
      extracted.directiveLines.lastOption
        .map(l => Position(l.lineNum, 0, l.lineStartOffset))
        .getOrElse(Position(0, 0, 0))
    )

    val (directives, parserDiagnostics) = Parser.parse(allTokens.toList)

    UsingDirectivesResult(
      directives = directives,
      codeOffset = extracted.codeOffset,
      diagnostics = parserDiagnostics ++ extracted.diagnostics
    )
  }

  def parse(content: String): UsingDirectivesResult =
    parse(content: IndexedSeq[Char])
}
