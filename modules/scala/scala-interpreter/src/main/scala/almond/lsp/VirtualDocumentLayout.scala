package almond.lsp

import com.google.gson.{JsonElement, JsonObject, JsonParser}
import org.eclipse.lsp4j.{Position, Range}

import scala.util.Try

/** Where the cells of a notebook are in a jupyterlab-lsp virtual document
  *
  * By default, the virtual document is made of the code of each cell (with magics possibly
  * replaced, keeping lines as is), followed by a new line, then by
  * [[VirtualDocumentLayout.blankLinesBetweenCells]] empty lines, except for the last cell.
  *
  * When the kernel asks for them, JupyterLab puts a header line before each cell instead of empty
  * lines after them (see [[VirtualDocumentLayout.splitWithHeaders]]).
  *
  * Lines and characters of cells are those of the virtual document, shifted by a number of lines.
  *
  * @param cellStartLines
  *   line of the virtual document where each cell starts
  * @param cellLineCounts
  *   number of lines of each cell
  */
final case class VirtualDocumentLayout(cellStartLines: Vector[Int], cellLineCounts: Vector[Int]) {

  /** Cell position of a position of the virtual document, if it's in a cell */
  def cellPosition(position: Position): Option[CellPosition] = {
    val line = position.getLine
    val cell = cellStartLines.lastIndexWhere(_ <= line)
    if (cell >= 0 && line < cellStartLines(cell) + cellLineCounts(cell))
      Some(CellPosition(cell, new Position(line - cellStartLines(cell), position.getCharacter)))
    else
      None
  }

  def position(position: CellPosition): Position =
    new Position(
      cellStartLines(position.cell) + position.position.getLine,
      position.position.getCharacter
    )

  def range(range: CellRange): Range =
    new Range(
      position(CellPosition(range.cell, range.range.getStart)),
      position(CellPosition(range.cell, range.range.getEnd))
    )
}

object VirtualDocumentLayout {

  /** Number of empty lines jupyterlab-lsp puts after each cell, but the last one */
  def blankLinesBetweenCells: Int = 2

  def apply(cells: Seq[CodeCell]): VirtualDocumentLayout = {
    val lineCounts = cells.map(cell => lineCount(cell.code)).toVector
    val startLines = lineCounts.scanLeft(0)(_ + _ + blankLinesBetweenCells).init
    VirtualDocumentLayout(startLines, lineCounts)
  }

  /** The virtual document jupyterlab-lsp builds for these cells */
  def text(cells: Seq[CodeCell]): String =
    cells.map(_.code + "\n").mkString("\n" * blankLinesBetweenCells)

  /** Splits a virtual document in cells
    *
    * The virtual document doesn't mark where cells start or end, so cell boundaries are guessed: a
    * non-empty line coming after at least [[blankLinesBetweenCells]] empty lines starts a new cell,
    * the last [[blankLinesBetweenCells]] of those empty lines being separators, and the ones before
    * them belonging to the previous cell. This splits cells having that many consecutive empty
    * lines in several cells, and can't tell empty cells or trailing empty lines apart.
    */
  def split(text: String): (Vector[CodeCell], VirtualDocumentLayout) = {
    // each cell is followed by a new line, including the last one
    val lines = text.stripSuffix("\n").split("\n", -1).toVector

    val cells      = Vector.newBuilder[String]
    val startLines = Vector.newBuilder[Int]
    var cellStart  = 0
    var emptyLines = 0
    for ((line, idx) <- lines.zipWithIndex)
      if (line.isEmpty)
        emptyLines += 1
      else {
        if (idx > 0 && emptyLines >= blankLinesBetweenCells) {
          val cellEnd = idx - blankLinesBetweenCells
          cells += lines.slice(cellStart, cellEnd).mkString("\n")
          startLines += cellStart
          cellStart = idx
        }
        emptyLines = 0
      }
    cells += lines.drop(cellStart).mkString("\n")
    startLines += cellStart

    val cells0 = cells.result().map(CodeCell(_))
    (cells0, VirtualDocumentLayout(startLines.result(), cells0.map(c => lineCount(c.code))))
  }

  /** Splits a virtual document whose cells are each preceded by a header line
    *
    * Header lines are made of a prefix, a space, and a JSON object like
    * {{{
    * {"id": "…", "index": 2, "cell_type": "code", "lines": 3, "execution_count": 4,
    *  "execution_state": "idle", "edited_since_run": false}
    * }}}
    * where `lines` is the number of lines of the cell, following its header line. Non-code cells
    * appear as an empty line, and are dropped here. A header line that can't be read starts a code
    * cell, running up to the next line with the prefix.
    *
    * @return
    *   `None` if the document doesn't start with a header line
    */
  def splitWithHeaders(
    text: String,
    prefix: String
  ): Option[(Vector[CodeCell], VirtualDocumentLayout)] = {
    // each cell is followed by a new line, including the last one
    val lines = text.stripSuffix("\n").split("\n", -1).toVector
    if (lines.headOption.exists(_.startsWith(prefix))) {
      val cells      = Vector.newBuilder[CodeCell]
      val startLines = Vector.newBuilder[Int]
      val lineCounts = Vector.newBuilder[Int]
      var idx        = 0
      while (idx < lines.length) {
        val headerOpt = CellHeader.parse(lines(idx).stripPrefix(prefix))
        val start     = idx + 1
        val count = headerOpt.flatMap(_.lines).filter(n => start + n <= lines.length).getOrElse {
          val next = lines.indexWhere(_.startsWith(prefix), start)
          (if (next < 0) lines.length else next) - start
        }
        if (headerOpt.forall(_.cellType.forall(_ == "code"))) {
          cells += CodeCell(
            lines.slice(start, start + count).mkString("\n"),
            headerOpt.flatMap(_.id),
            headerOpt.flatMap(_.executionCount)
          )
          startLines += start
          lineCounts += count
        }
        idx = start + count
      }
      Some((cells.result(), VirtualDocumentLayout(startLines.result(), lineCounts.result())))
    }
    else
      None
  }

  /** The virtual document JupyterLab builds for these cells, with header lines
    *
    * Only the cell id and execution count are put in headers.
    */
  def textWithHeaders(cells: Seq[CodeCell], prefix: String): String =
    cells.zipWithIndex.map {
      case (cell, idx) =>
        val header = new JsonObject
        cell.id.foreach(header.addProperty("id", _))
        header.addProperty("index", idx)
        header.addProperty("cell_type", "code")
        header.addProperty("lines", lineCount(cell.code))
        cell.executionCount.foreach(n => header.addProperty("execution_count", n))
        s"$prefix $header\n${cell.code}\n"
    }.mkString

  /** What [[splitWithHeaders]] reads from header lines */
  private final case class CellHeader(
    id: Option[String],
    cellType: Option[String],
    lines: Option[Int],
    executionCount: Option[Int]
  )

  private object CellHeader {
    def parse(json: String): Option[CellHeader] =
      Try(JsonParser.parseString(json).getAsJsonObject).toOption.map { obj =>
        def field[T](name: String)(read: JsonElement => T): Option[T] =
          Option(obj.get(name))
            .filter(e => !e.isJsonNull)
            .flatMap(e => Try(read(e)).toOption)
        CellHeader(
          id = field("id")(_.getAsString),
          cellType = field("cell_type")(_.getAsString),
          lines = field("lines")(_.getAsInt).filter(_ >= 0),
          executionCount = field("execution_count")(_.getAsInt)
        )
      }
  }

  private def lineCount(code: String): Int =
    code.count(_ == '\n') + 1
}
