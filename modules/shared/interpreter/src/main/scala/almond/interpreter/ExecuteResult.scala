package almond.interpreter

import almond.interpreter.api.DisplayData

sealed abstract class ExecuteResult(val success: Boolean) extends Product with Serializable {
  def asError: Option[ExecuteResult.Error] =
    this match {
      case err: ExecuteResult.Error => Some(err)
      case _ => None
    }
}

object ExecuteResult {

  final case class Success(data: DisplayData = DisplayData.empty) extends ExecuteResult(success = true)

  final case class Error(
    name: String,
    message: String,
    stackTrace: List[String]
  ) extends ExecuteResult(success = false)

  object Error {
    def apply(msg: String): Error =
      Error("", msg, Nil)
  }

  case object Abort extends ExecuteResult(success = false)

}
