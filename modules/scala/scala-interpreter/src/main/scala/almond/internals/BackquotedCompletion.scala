package almond.internals

/** Helpers to complete identifiers written between backticks, like `` `a-b` ``
  *
  * The ranges of code that the compilers say completions should replace don't account well for
  * backticks (see https://github.com/almond-sh/almond/issues/628). So we find the backquoted
  * identifier around the cursor from the code itself, and adjust that range and the completions
  * accordingly.
  */
object BackquotedCompletion {

  /** A backquoted identifier the cursor is in, or right after
    *
    * @param open
    *   index of the opening backtick
    * @param closeOpt
    *   index of the closing backtick, if any
    */
  final case class Ident(open: Int, closeOpt: Option[Int]) {

    /** Code to pass to the compiler, so that the identifier is complete */
    def codeToComplete(code: String, pos: Int): String =
      if (closeOpt.isEmpty) code.take(pos) + "`" + code.drop(pos)
      else code

    /** Range of code that completions should replace, and how to transform completions
      *
      * @param pos
      *   cursor position
      * @param start
      *   start of the range to replace, as computed by the compiler
      * @return
      *   `None` if the compiler completions aren't about this identifier
      */
    def adjust(pos: Int, start: Int): Option[(Int, Int, String => String)] = {
      val close = closeOpt.getOrElse(pos)
      if (start < open || start > close + 1) None
      else
        closeOpt match {
          case None =>
            // replace the opening backtick and what follows; completions that start with a
            // backtick are kept as is (dependency completions purposely lack a closing one)
            Some((open, pos, s => if (s.startsWith("`")) s else "`" + s + "`"))
          case Some(close) if pos <= close =>
            // replace what's between the backticks, and keep them
            Some((open + 1, close, unquote))
          case Some(close) =>
            // replace the whole identifier, backticks included
            Some((open, close + 1, s => "`" + unquote(s) + "`"))
        }
    }
  }

  private def unquote(s: String): String =
    s.stripPrefix("`").stripSuffix("`")

  def find(code: String, pos: Int): Option[Ident] = {
    // backquoted identifiers can't span several lines
    val lineStart = code.lastIndexOf('\n', pos - 1) + 1
    val backticks = (lineStart until pos).filter(code(_) == '`')
    if (backticks.length % 2 == 1) {
      val closeIdx = code.indexWhere(c => c == '`' || c == '\n', pos)
      Some(Ident(backticks.last, Some(closeIdx).filter(i => i >= 0 && code(i) == '`')))
    }
    else if (backticks.nonEmpty && backticks.last == pos - 1)
      Some(Ident(backticks(backticks.length - 2), Some(pos - 1)))
    else
      None
  }

}
