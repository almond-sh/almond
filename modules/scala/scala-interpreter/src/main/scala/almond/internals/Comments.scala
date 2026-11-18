package almond.internals

import scala.annotation.tailrec

/** Finds whether a position of some code is in a comment
  *
  * The Scala 2 presentation compiler offers all the names in scope when completing in a comment, so
  * we look for comments ourselves before asking for completions. This only needs to know enough of
  * the Scala syntax to tell comments from string and character literals, and from identifiers
  * between backticks, that can contain `//` or `/ *`.
  */
object Comments {

  private sealed abstract class Context extends Product with Serializable
  // code, possibly in the `${…}` of an interpolated string, with the number of '{' not closed yet
  private final case class Code(openBraces: Int) extends Context
  // the literal part of an interpolated string
  private final case class Interpolated(triple: Boolean) extends Context

  private def isIdentifierPart(c: Char): Boolean =
    Character.isUnicodeIdentifierPart(c) || c == '$'

  /** Whether `pos` is in a comment of `code`, like the end of `// foo` or `/ * foo * /`
    *
    * A position right before the `//` or `/ *` of a comment, or right after the `* /` of a block
    * comment, isn't in it. A position at the end of the line of a line comment is.
    */
  def isInComment(code: String, pos: Int): Boolean = {

    def startsWith(prefix: String, idx: Int): Boolean =
      code.startsWith(prefix, idx)

    // index after the "*/" closing the block comment starting at idx, or -1 if it's not closed
    def blockCommentEnd(idx: Int): Int = {
      @tailrec
      def loop(i: Int, depth: Int): Int =
        if (i >= code.length) -1
        else if (startsWith("*/", i))
          if (depth == 1) i + 2
          else loop(i + 2, depth - 1)
        else if (startsWith("/*", i)) loop(i + 2, depth + 1)
        else loop(i + 1, depth)
      loop(idx + 2, 1)
    }

    // index after the end of the non-interpolated string starting at idx (unclosed ones
    // end with their line)
    def stringEnd(idx: Int, triple: Boolean): Int =
      if (triple) {
        val close = code.indexOf("\"\"\"", idx + 3)
        if (close < 0) code.length
        else {
          // the closing quotes are the last three ones, in things like """a""""
          var end = close + 3
          while (end < code.length && code(end) == '"') end += 1
          end
        }
      }
      else {
        @tailrec
        def loop(i: Int): Int =
          if (i >= code.length) code.length
          else
            code(i) match {
              case '\\' => loop(i + 2)
              case '"'  => i + 1
              case '\n' => i
              case _    => loop(i + 1)
            }
        loop(idx + 1)
      }

    // index after the character literal starting at idx, or idx + 1 if there's none
    // (Scala 2 symbol literals, Scala 3 quotes)
    def charLiteralEnd(idx: Int): Int =
      if (startsWith("\\", idx + 1)) {
        val close = code.indexOf('\'', idx + 2)
        if (close < 0 || close - idx > 8) idx + 1 else close + 1
      }
      else if (idx + 2 < code.length && code(idx + 2) == '\'' && code(idx + 1) != '\n') idx + 3
      else idx + 1

    @tailrec
    def loop(i: Int, contexts: List[Context]): Boolean =
      if (i >= pos || i >= code.length) false
      else
        contexts match {
          case Code(openBraces) :: rest =>
            val c = code(i)
            if (startsWith("//", i)) {
              val lineEnd = code.indexOf('\n', i)
              lineEnd < 0 || pos <= lineEnd || loop(lineEnd + 1, contexts)
            }
            else if (startsWith("/*", i)) {
              val end = blockCommentEnd(i)
              end < 0 || pos < end || loop(end, contexts)
            }
            else if (c == '"') {
              val triple       = startsWith("\"\"\"", i)
              val interpolated = i > 0 && isIdentifierPart(code(i - 1))
              if (interpolated)
                loop(i + (if (triple) 3 else 1), Interpolated(triple) :: contexts)
              else
                loop(stringEnd(i, triple), contexts)
            }
            else if (c == '\'')
              loop(charLiteralEnd(i), contexts)
            else if (c == '`') {
              // backquoted identifiers can't span several lines
              val end = code.indexWhere(c => c == '`' || c == '\n', i + 1)
              if (end < 0) false else loop(end + 1, contexts)
            }
            else if (c == '{')
              loop(i + 1, Code(openBraces + 1) :: rest)
            else if (c == '}')
              if (openBraces == 0) loop(i + 1, if (rest.isEmpty) contexts else rest)
              else loop(i + 1, Code(openBraces - 1) :: rest)
            else
              loop(i + 1, contexts)

          case Interpolated(triple) :: rest =>
            if (triple && startsWith("\"\"\"", i)) {
              var end = i + 3
              while (end < code.length && code(end) == '"') end += 1
              loop(end, rest)
            }
            else if (!triple && (code(i) == '"' || code(i) == '\n'))
              loop(i + 1, rest)
            else if (startsWith("${", i))
              loop(i + 2, Code(0) :: contexts)
            else if (startsWith("$$", i) || (!triple && code(i) == '\\'))
              loop(i + 2, contexts)
            else
              loop(i + 1, contexts)

          case Nil =>
            false
        }

    loop(0, List(Code(0)))
  }
}
