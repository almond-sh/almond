package almond.lsp

import org.eclipse.lsp4j.Position

/** Position in a cell of a notebook, line and character being relative to the cell */
final case class CellPosition(cell: Int, position: Position)
