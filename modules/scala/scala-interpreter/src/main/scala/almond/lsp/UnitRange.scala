package almond.lsp

import org.eclipse.lsp4j.Range

/** Range in the user code of a source unit */
final case class UnitRange(unit: SourceUnit, range: Range)
