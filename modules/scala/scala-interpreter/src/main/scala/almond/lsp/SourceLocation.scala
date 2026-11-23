package almond.lsp

import org.eclipse.lsp4j.Location

/** Location returned by language features */
sealed abstract class SourceLocation extends Product with Serializable

object SourceLocation {

  /** Location in the user code of a source unit */
  final case class InUnit(range: UnitRange) extends SourceLocation

  /** Location outside of the session code, like library sources */
  final case class External(location: Location) extends SourceLocation
}
