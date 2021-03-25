// from https://github.com/scalameta/metals/blob/f4ac29634cdf4049708fc40d4c0fc98ffe343ae5/mtags/src/main/scala/scala/meta/internal/metals/EmptySymbolDocumentation.scala
package almond.internals

import java.util
import java.util.Collections

import scala.meta.pc.SymbolDocumentation

/**
 * A symbol documenation with all empty values
 */
object EmptySymbolDocumentation extends SymbolDocumentation {
  override def symbol(): String = ""
  override def displayName(): String = ""
  override def docstring(): String = ""
  override def defaultValue(): String = ""
  override def typeParameters(): util.List[SymbolDocumentation] =
    Collections.emptyList()
  override def parameters(): util.List[SymbolDocumentation] =
    Collections.emptyList()
}
