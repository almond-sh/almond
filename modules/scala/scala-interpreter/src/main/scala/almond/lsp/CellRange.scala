package almond.lsp

import org.eclipse.lsp4j.Range

/** Range in a cell of a notebook, lines and characters being relative to the cell */
final case class CellRange(cell: Int, range: Range)
