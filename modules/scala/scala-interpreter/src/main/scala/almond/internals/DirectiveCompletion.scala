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

  /** Whether a dependency being typed is at its version, like `org.typelevel::cats-core:` or
    * `org.typelevel:cats-core_2.13:2.1`, rather than at its organization or module name
    */
  def isVersion(dependencyPrefix: String): Boolean = {
    val idx = dependencyPrefix.lastIndexOf(':')
    idx >= 0 && {
      // the module name is the last non-empty part before the last ':', the parts in between
      // being empty in 'org::name:' (Scala module) or 'org:::name:' (full cross-version one)
      val parts = dependencyPrefix.substring(0, idx).split(":", -1)
      parts.length >= 2 && parts.last.nonEmpty
    }
  }

  /** Sorts dependency completions, and removes duplicates from them
    *
    * Versions are sorted semantically, highest first, with the coursier version ordering (the one
    * used to pick versions when resolving dependencies). Organizations and module names are left
    * as is (in the order of the repository listings, sorted alphabetically for Maven Central).
    */
  def sortCompletions(dependencyPrefix: String, completions: Seq[String]): Seq[String] = {
    // several repositories may list the same organizations, module names, or versions
    val completions0 = completions.distinct
    if (isVersion(dependencyPrefix))
      completions0.sortWith((a, b) => coursierapi.Version.compare(a, b) > 0)
    else
      completions0
  }

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
