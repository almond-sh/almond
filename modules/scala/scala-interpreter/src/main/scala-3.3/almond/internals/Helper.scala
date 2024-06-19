package almond.internals

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.util.SourceFile

object Helper {
  def nonSuspendableCompilationUnit(source: SourceFile): CompilationUnit =
    new CompilationUnit(source):
      override def isSuspendable: Boolean = false
}
