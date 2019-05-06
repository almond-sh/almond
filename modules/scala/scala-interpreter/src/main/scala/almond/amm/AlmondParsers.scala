package almond.amm

import ammonite.interp.Parsers.Prelude
import fastparse._
import fastparse.ScalaWhitespace._
import scalaparse.Scala._

object AlmondParsers {

  // same as the methods with the same name in ammonite.interp.Parsers, but keeping the type aside in LHS

  def PatVarSplitter[_: P] = {
    def Prefixes = P(Prelude ~ (`var` | `val`))
    def Lhs = P( Prefixes ~/ BindPattern.rep(1, "," ~/ Pass) )
    def TypeAnnotation = P( (`:` ~/ Type.!).? )
    P( Lhs.! ~ TypeAnnotation ~ (`=` ~/ WL ~ StatCtx.Expr.!) ~ End )
  }

}
