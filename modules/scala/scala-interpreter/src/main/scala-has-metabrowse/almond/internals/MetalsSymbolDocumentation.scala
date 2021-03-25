// from https://github.com/scalameta/metals/blob/f4ac29634cdf4049708fc40d4c0fc98ffe343ae5/mtags/src/main/scala-2/scala/meta/internal/metals/MetalsSymbolDocumentation.scala
package almond.internals

import java.util

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.SymbolDocumentation

case class MetalsSymbolDocumentation(
    symbol: String,
    displayName: String,
    docstring: String,
    defaultValue: String = "",
    typeParameters: util.List[SymbolDocumentation] = Nil.asJava,
    parameters: util.List[SymbolDocumentation] = Nil.asJava
) extends SymbolDocumentation
