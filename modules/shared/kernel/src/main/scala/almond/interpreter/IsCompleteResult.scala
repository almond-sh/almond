package almond.interpreter

sealed abstract class IsCompleteResult(val status: String) extends Product with Serializable

object IsCompleteResult {

  case object Complete   extends IsCompleteResult("complete")
  case object Incomplete extends IsCompleteResult("incomplete")
  case object Invalid    extends IsCompleteResult("invalid")

}
