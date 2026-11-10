// Based on https://github.com/scala/scala3/blob/0c86ed878fb69a05e4ea5e87cfe0c527f93a3f21/directives-parser/src/main/scala/dotty/tools/directives/CommentExtractor.scala

package almond.directivehandler.parser

import scala.collection.mutable

/** Scans a source file and extracts its `//> using` directive lines.
  *
  * Directives must appear in a preamble at the top of the file, before the first line of code. Only
  * blank lines, a `#!` shebang on the first line, line comments and (possibly nested) block
  * comments can precede or be interleaved with them. Directives found after code are ignored, with
  * a warning. The lines in the result always have exactly one space between `//>` and `using`.
  */
object CommentExtractor {

  private val UsingDirectiveRegex = """^//>\s*using(?:\s|$)""".r

  private val bom = 0xfeff.toChar

  private def stripLeading(s: String): String =
    s.dropWhile(Character.isWhitespace(_))

  def extract(rawContent: IndexedSeq[Char]): ExtractorResult = {
    val content =
      if (rawContent.nonEmpty && rawContent(0) == bom) rawContent.drop(1)
      else rawContent
    val length      = content.length
    val bomOffset   = rawContent.length - content.length
    val diagnostics = mutable.ArrayBuffer.empty[UsingDirectiveDiagnostic]
    val directives  = mutable.ArrayBuffer.empty[DirectiveLine]

    var offset    = 0
    var lineNum   = 0
    var codeStart = -1 // -1 means not yet found

    def currentLineText(lineStartOff: Int): String = {
      var end = lineStartOff
      while (end < length && content(end) != '\n') end += 1
      content.slice(lineStartOff, end).mkString
    }

    // Skip a block comment starting at `startOff` (which points to `/` of `/*`).
    // Returns the offset just after the closing `*/`, and the updated line number.
    // Supports nested block comments to match the Scala compiler.
    def skipBlockComment(startOff: Int, startLine: Int): (Int, Int) = {
      var off   = startOff + 2 // skip `/*`
      var ln    = startLine
      var depth = 1
      while (off < length - 1 && depth > 0)
        if (content(off) == '/' && content(off + 1) == '*') {
          depth += 1
          off += 2
        }
        else if (content(off) == '*' && content(off + 1) == '/') {
          depth -= 1
          off += 2
        }
        else {
          if (content(off) == '\n') ln += 1
          off += 1
        }
      if (depth > 0 && off < length) {
        if (content(off) == '\n') ln += 1
        off += 1
      }
      (off, ln)
    }

    def skipLine(lineStart: Int, lineContent: String): Unit = {
      offset = lineStart + lineContent.length
      if (offset < length && content(offset) == '\n') offset += 1
      lineNum += 1
    }

    while (offset < length && codeStart < 0) {
      val lineStart = offset

      // Determine what kind of line this is (without consuming it yet)
      val lineContent = currentLineText(lineStart)
      val trimmed     = stripLeading(lineContent)

      if (trimmed.isEmpty)
        // Blank line
        skipLine(lineStart, lineContent)
      else if (lineNum == 0 && trimmed.startsWith("#!"))
        // Shebang: skip only on the very first line
        skipLine(lineStart, lineContent)
      else if (trimmed.startsWith("/*")) {
        // Block comment: skip the whole comment, may span multiple lines
        val commentStartOff         = lineStart + lineContent.indexOf("/*")
        val (afterComment, newLine) = skipBlockComment(commentStartOff, lineNum)
        offset = afterComment
        lineNum = newLine
        // Skip to end of the current line (in case there's trailing whitespace after `*/`)
        while (offset < length && content(offset) != '\n') {
          val c = content(offset)
          if (c != ' ' && c != '\t' && c != '\r')
            // Non-blank, non-comment content after `*/` on the same line -> code starts
            codeStart = lineStart
          offset += 1
        }
        if (codeStart < 0) {
          if (offset < length && content(offset) == '\n') offset += 1
          lineNum += 1
        }
      }
      else if (trimmed.startsWith("//") && !trimmed.startsWith("//>"))
        // Line comment (not a directive)
        skipLine(lineStart, lineContent)
      else if (trimmed.startsWith("//>")) {
        val withoutLeading = trimmed.drop(3).dropWhile { c =>
          Character.isWhitespace(c) || Character.isSpaceChar(c)
        }
        if (withoutLeading.startsWith("using")) {
          val adjustedOffset = lineStart + lineContent.indexOf("//>") + bomOffset
          // Normalize the result to have exactly one space.
          directives += DirectiveLine("//> " + withoutLeading, lineNum, adjustedOffset)
          skipLine(lineStart, lineContent)
        }
        else
          codeStart = lineStart
      }
      else
        // First code line
        codeStart = lineStart
    }

    // If we never found code, codeOffset is end of file
    val codeOffset = if (codeStart >= 0) codeStart else length

    // Continue scanning the rest of the file for post-code directives
    if (codeStart >= 0) {
      offset = codeStart
      var ln = lineNum

      while (offset < length) {
        val lineStart   = offset
        val lineContent = currentLineText(lineStart)
        val trimmed     = stripLeading(lineContent)

        if (trimmed.startsWith("/*")) {
          // Skip block comment
          val commentStartOff         = lineStart + lineContent.indexOf("/*")
          val (afterComment, newLine) = skipBlockComment(commentStartOff, ln)
          offset = afterComment
          ln = newLine
          while (offset < length && content(offset) != '\n') offset += 1
          if (offset < length && content(offset) == '\n') offset += 1
          ln += 1
        }
        else {
          if (
            trimmed.startsWith("//> using") ||
            UsingDirectiveRegex.findFirstIn(trimmed).isDefined
          ) {
            val msg     = s"Ignoring using directive found after Scala code: ${trimmed.trim}"
            val linePos = Position(ln, 0, lineStart + bomOffset)
            diagnostics += UsingDirectiveDiagnostic(msg, DiagnosticSeverity.Warning, linePos)
          }
          offset = lineStart + lineContent.length
          if (offset < length && content(offset) == '\n') offset += 1
          ln += 1
        }
      }
    }

    ExtractorResult(directives.toList, codeOffset + bomOffset, diagnostics.toList)
  }
}
