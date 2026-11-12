package almond.internals

import almond.directivehandler.parser.CommentExtractor

/** Helpers to complete the dependencies of `//> using dep` directives
  *
  * The compiler only sees directives as comments, so we find the directive value the cursor is in
  * from the code itself, and complete it with the dependency completer of Ammonite (the one
  * `import $ivy` relies on).
  */
object DirectiveCompletion {

  /** The value of a dependency directive the cursor is in
    *
    * @param start
    *   index of the first character of the value in the code (after its opening quote, if any)
    * @param prefix
    *   content of the value, up to the cursor (empty if the cursor is not in a value yet, like
    *   right after `//> using dep `)
    */
  final case class DependencyValue(start: Int, prefix: String)

  private val dependencyKeys = Set("dep", "deps", "dependencies", "lib", "libs")

  private val headerRegex = """//>\s*using\s+([^\s"`]+)\s""".r

  /** Finds the dependency directive value the cursor is in, if any
    *
    * Only directives of the preamble of the cell, before any code, are considered, like when
    * running the cell.
    */
  def findDependency(code: String, pos: Int): Option[DependencyValue] =
    if (pos < 0 || pos > code.length || !code.contains("//>")) None
    else
      CommentExtractor.extract(code.toIndexedSeq)
        .directiveLines
        .iterator
        .map(_.lineStartOffset) // offset of the "//>" of the line in the code
        .find { start =>
          val nl      = code.indexOf('\n', start)
          val lineEnd = if (nl < 0) code.length else nl
          start <= pos && pos <= lineEnd
        }
        .flatMap { start =>
          valuePrefix(code.substring(start, pos)).map {
            case (relStart, prefix) =>
              DependencyValue(start + relStart, prefix)
          }
        }

  /** Finds the value being typed at the end of a directive line, cut at the cursor
    *
    * @param line
    *   content of the directive line, from its `//>` up to the cursor
    * @return
    *   index of the beginning of the value in `line`, and the value itself, if `line` is a
    *   dependency directive
    */
  private def valuePrefix(line: String): Option[(Int, String)] =
    headerRegex.findPrefixMatchOf(line)
      .filter(m => dependencyKeys(m.group(1)))
      .map { m =>
        // Same rules as almond.directivehandler.parser.Lexer, without escape sequences in quoted
        // values (dependencies don't need them), so that indices in values match those in the code
        var idx        = m.end
        var valueStart = idx
        var inValue    = false
        var inQuotes   = false
        while (idx < line.length) {
          val c = line.charAt(idx)
          if (inQuotes) {
            if (c == '"') {
              inQuotes = false
              inValue = false
            }
          }
          else if (c == '"') {
            inQuotes = true
            inValue = true
            valueStart = idx + 1
          }
          else if (Character.isWhitespace(c))
            inValue = false
          else if (
            c == ',' && (idx + 1 >= line.length || Character.isWhitespace(line.charAt(idx + 1)))
          )
            inValue = false
          else if (!inValue) {
            inValue = true
            valueStart = idx
          }
          idx += 1
        }
        if (inValue) (valueStart, line.substring(valueStart))
        else (line.length, "")
      }
}
