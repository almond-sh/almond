package almond.amm

import fastparse._
import fastparse.ScalaWhitespace._
import scalaparse.Scala._

object AlmondParsers {

  private def Prelude[X: P] = P((Annot ~ OneNLMax).rep ~ (Mod ~/ Pass).rep)

  // same as the methods with the same name in ammonite.interp.Parsers, but keeping the type aside in LHS

  def PatVarSplitter[X: P] = {
    def Prefixes       = P(Prelude ~ (`var` | `val`))
    def Lhs            = P(Prefixes ~/ VarId)
    def TypeAnnotation = P((`:` ~/ Type.!).?)
    // also returns the index of the RHS in the input
    P(Lhs.! ~ TypeAnnotation ~ (`=` ~/ WL ~ Index ~ StatCtx.Expr.!) ~ End)
  }

}
