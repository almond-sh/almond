package almond.interpreter

sealed abstract class IsCompleteResult(val status: String) extends Product with Serializable

object IsCompleteResult {

  case object Complete extends IsCompleteResult("complete")

  /** @param indent
    *   characters to prefix the next line with, as a hint for the frontend
    */
  final case class Incomplete(indent: String) extends IsCompleteResult("incomplete")
  case object Invalid                         extends IsCompleteResult("invalid")

}
