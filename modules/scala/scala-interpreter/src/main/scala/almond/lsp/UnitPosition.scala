package almond.lsp

import org.eclipse.lsp4j.Position

/** Position in the user code of a source unit */
final case class UnitPosition(unit: SourceUnit, position: Position)
