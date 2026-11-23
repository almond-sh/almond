package almond.lsp

import org.eclipse.lsp4j.{Position, TextDocumentContentChangeEvent}

/** Applies text document changes, as sent by clients */
private[lsp] object TextChanges {

  def apply(text: String, change: TextDocumentContentChangeEvent): String =
    Option(change.getRange) match {
      case None => change.getText
      case Some(range) =>
        text.substring(0, offset(text, range.getStart)) +
          change.getText +
          text.substring(offset(text, range.getEnd))
    }

  /** Offset of a position in a text, LSP characters being UTF-16 code units, like JVM chars */
  def offset(text: String, position: Position): Int = {
    var lineStart = 0
    var line      = 0
    while (line < position.getLine && lineStart <= text.length) {
      val nl = text.indexOf('\n', lineStart)
      lineStart = if (nl < 0) text.length + 1 else nl + 1
      line += 1
    }
    math.min(lineStart + position.getCharacter, text.length)
  }
}
