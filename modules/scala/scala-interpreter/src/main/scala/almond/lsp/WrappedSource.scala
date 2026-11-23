package almond.lsp

import org.eclipse.lsp4j.{Position, Range}

/** Code passed to the compiler for a `.sc` file: the top wrapper, the user code, the bottom wrapper
  *
  * The top wrapper ends with a new line, so that the user code starts at the beginning of a line.
  *
  * @param uri
  *   URI of the file with the wrapped code
  * @param topWrapperLines
  *   number of lines of the top wrapper
  * @param userCodeLines
  *   number of lines of the user code
  */
final case class WrappedSource(uri: String, topWrapperLines: Int, userCodeLines: Int) {

  /** Position in the wrapped code of a position in the user code */
  def toWrapped(position: Position): Position =
    new Position(position.getLine + topWrapperLines, position.getCharacter)

  /** Position in the user code of a position in the wrapped code, if it's in the user code */
  def fromWrapped(position: Position): Option[Position] = {
    val line = position.getLine - topWrapperLines
    if (line >= 0 && line < userCodeLines) Some(new Position(line, position.getCharacter))
    else None
  }

  def fromWrapped(range: Range): Option[Range] =
    for {
      start <- fromWrapped(range.getStart)
      end   <- fromWrapped(range.getEnd)
    } yield new Range(start, end)
}
