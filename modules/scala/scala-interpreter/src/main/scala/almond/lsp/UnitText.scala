package almond.lsp

import org.eclipse.lsp4j.{Position, Range}

/** Code of a source unit, as passed to language servers that work on the file of the unit
  *
  * For pending units, this has the code of the pending cells before them too, so that they see the
  * definitions of these cells, which weren't run yet: language servers like Plasmon compile the
  * code of a file along with the classes of the inputs run so far, and wouldn't know about these
  * definitions otherwise. The code of each of these cells is nested in a class in the code of the
  * cell before it, and the code of the unit in a class in the code of the last of these cells, so
  * that definitions of later cells shadow those of earlier ones, like they do when running cells in
  * order. Cells whose code doesn't parse, like cells being edited, are left out, as their syntax
  * errors would prevent the code after them from being compiled.
  *
  * Like the code of the unit, the code of each of these cells starts at the beginning of a line, so
  * that positions in the code of a cell and in this text only differ by their line.
  *
  * @param parts
  *   the units whose code is in `text`, in order, the last one being `unit`
  */
final case class UnitText(unit: SourceUnit, text: String, parts: Seq[UnitText.Part]) {

  private def ownPart = parts.last

  /** Position in this text of a position in the code of `unit` */
  def toText(position: Position): Position =
    new Position(position.getLine + ownPart.line, position.getCharacter)

  /** Position in the code of a unit of a position in this text, if it's in the code of one */
  def fromText(position: Position): Option[UnitPosition] =
    parts.find(_.contains(position.getLine)).map { part =>
      UnitPosition(part.unit, new Position(position.getLine - part.line, position.getCharacter))
    }

  /** Range in the code of a unit of a range of this text, if it's in the code of one */
  def fromText(range: Range): Option[UnitRange] =
    for {
      start <- fromText(range.getStart)
      end   <- fromText(range.getEnd)
      if end.unit == start.unit
    } yield UnitRange(start.unit, new Range(start.position, end.position))

  /** Range in the code of `unit` of a range of this text, if it's in that code */
  def unitRange(range: Range): Option[Range] =
    fromText(range).filter(_.unit == unit).map(_.range)
}

object UnitText {

  /** The code of `unit`, that starts at line `line` of the text, and spans `lines` lines */
  final case class Part(unit: SourceUnit, line: Int, lines: Int) {
    def contains(line0: Int): Boolean =
      line0 >= line && line0 < line + lines
  }

  private def lineCount(code: String): Int =
    code.count(_ == '\n') + 1

  /** Prefix of the names of the classes the code of pending cells is nested in
    *
    * Completions offer these classes, which users aren't meant to see.
    */
  def classNamePrefix: String = "almond$pending$cell"

  /** Name of the class the code of the pending cell `cell` is nested in */
  private def className(cell: Int): String =
    classNamePrefix + cell

  /** @param parses
    *   whether code parses, and can be nested in a class
    */
  def apply(unit: SourceUnit, parses: String => Boolean): UnitText =
    unit match {
      case pending: SourceUnit.Pending =>
        val previous = pending.previous.filter { cell =>
          val code = cell.code.trim
          // package clauses can't be nested in classes
          code.nonEmpty && !code.startsWith("package ") && parses(cell.code)
        }
        val text  = new StringBuilder
        val parts = Vector.newBuilder[Part]
        var line  = 0
        for ((cell, idx) <- previous.iterator.zipWithIndex) {
          if (idx > 0) {
            text ++= s"final class ${className(cell.cell)} {\n"
            line += 1
          }
          parts += Part(cell, line, lineCount(cell.code))
          text ++= cell.code
          text ++= "\n"
          line += lineCount(cell.code)
        }
        if (previous.nonEmpty)
          text ++= s"final class ${className(pending.cell)} {\n"
        parts += Part(unit, line + (if (previous.isEmpty) 0 else 1), lineCount(unit.code))
        text ++= unit.code
        // on lines of their own, so that they're not commented by a line comment at the end
        text ++= "\n}" * previous.length
        UnitText(unit, text.result(), parts.result())
      case _ =>
        UnitText(unit, unit.code, Seq(Part(unit, 0, lineCount(unit.code))))
    }
}
