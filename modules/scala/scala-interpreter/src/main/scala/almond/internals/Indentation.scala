package almond.internals

/** Computes the indentation hint sent alongside `is_complete_reply` for incomplete code
  */
object Indentation {

  private def indentUnit = "  "

  // If the last line ends with one of these, the next line is expected to be indented one level
  // more than it
  private val openingSuffixes = Seq("{", "(", "[", "=", "=>", ":")
  private val openingKeywords =
    Set("then", "else", "do", "yield", "with", "match", "try", "catch", "finally")

  /** Characters to prefix the line following `code` with
    *
    * This relies on the last non-blank line of `code`: its indentation is kept, and an extra level
    * is added if it looks like it opens a block (ends with `{`, `=`, or a keyword like `then`, …)
    */
  def nextLine(code: String): String =
    code.linesIterator.toVector.reverseIterator.find(_.trim.nonEmpty) match {
      case None => ""
      case Some(line) =>
        val baseIndent = line.takeWhile(c => c == ' ' || c == '\t')
        val content    = line.trim
        val opensBlock =
          openingSuffixes.exists(content.endsWith) ||
          openingKeywords.contains(content.split("\\s+").last)
        if (opensBlock) baseIndent + indentUnit
        else baseIndent
    }

}
