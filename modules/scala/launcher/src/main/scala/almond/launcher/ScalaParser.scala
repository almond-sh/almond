package almond.launcher

import fastparse._

import fastparse.ScalaWhitespace._
import scalaparse.Scala._

object ScalaParser {

  def AllWS[X: P] = Start ~ WS ~ End

  def hasActualCode(code: String): Boolean =
    !parse(code, AllWS(_)).isSuccess

}
