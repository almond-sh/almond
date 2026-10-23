package almond.amm

/** Helpers for the Python output style, where only the value of the last expression of a cell gets
  * printed, like the Python kernel does
  */
private[almond] object PythonOutputStyle {

  /** Code printing the value of `ident` alone, without its name or type */
  def printerCode(ident: String): String =
    s"""_root_.almond
       |  .api
       |  .JupyterAPIHolder
       |  .value
       |  .Internal
       |  .printValueOnly($ident)""".stripMargin

  /** The name the Ammonite preprocessors give to the result of the expression at index `idx`, in a
    * cell made of `count` statements
    */
  def resultName(resultIndex: String, idx: Int, count: Int): String = {
    val suffix = if (count > 1) "_" + idx else ""
    "res" + resultIndex + suffix
  }

  /** Whether code ends with a semicolon, ignoring trailing whitespace and comments
    *
    * Like in the Python kernel, a trailing semicolon silences the value of the last expression of a
    * cell.
    */
  def endsWithSemicolon(code: String): Boolean =
    lastSignificantChar(code).contains(';')

  /** The last character of code that isn't whitespace or part of a comment
    *
    * String and character literals are skipped, so that their content isn't mistaken for comments.
    */
  private def lastSignificantChar(code: String): Option[Char] = {
    val len  = code.length
    var idx  = 0
    var last = Option.empty[Char]
    def skipQuotedLiteral(quote: Char): Unit = {
      idx += 1
      while (idx < len && code.charAt(idx) != quote && code.charAt(idx) != '\n') {
        if (code.charAt(idx) == '\\') idx += 1
        idx += 1
      }
      idx += 1
      last = Some(quote)
    }
    while (idx < len) {
      val c = code.charAt(idx)
      if (code.startsWith("//", idx)) {
        val end = code.indexOf('\n', idx)
        idx = if (end < 0) len else end
      }
      else if (code.startsWith("/*", idx)) {
        // block comments can be nested in Scala
        var depth = 1
        idx += 2
        while (idx < len && depth > 0)
          if (code.startsWith("/*", idx)) {
            depth += 1
            idx += 2
          }
          else if (code.startsWith("*/", idx)) {
            depth -= 1
            idx += 2
          }
          else
            idx += 1
      }
      else if (code.startsWith("\"\"\"", idx)) {
        val end = code.indexOf("\"\"\"", idx + 3)
        idx =
          if (end < 0) len
          else {
            // closing quotes of multi-line strings can be preceded by quotes from the string itself
            var idx0 = end + 3
            while (idx0 < len && code.charAt(idx0) == '"')
              idx0 += 1
            idx0
          }
        last = Some('"')
      }
      else if (c == '"')
        skipQuotedLiteral('"')
      else if (
        c == '\'' && idx + 2 < len &&
        (code.charAt(idx + 1) == '\\' || code.charAt(idx + 2) == '\'')
      )
        skipQuotedLiteral('\'')
      else {
        if (!c.isWhitespace)
          last = Some(c)
        idx += 1
      }
    }
    last
  }
}
