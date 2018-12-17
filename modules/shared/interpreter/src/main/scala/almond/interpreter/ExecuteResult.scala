package almond.interpreter

import almond.interpreter.api.DisplayData

sealed abstract class ExecuteResult(val success: Boolean) extends Product with Serializable {

  def asSuccess: Option[ExecuteResult.Success] =
    this match {
      case s: ExecuteResult.Success => Some(s)
      case _ => None
    }

  def asError: Option[ExecuteResult.Error] =
    this match {
      case err: ExecuteResult.Error => Some(err)
      case _ => None
    }
}

object ExecuteResult {

  /**
    * [[ExecuteResult]], if execution was successful.
    *
    * @param data: output data for the code that was run
    */
  final case class Success(data: DisplayData = DisplayData.empty) extends ExecuteResult(success = true)

  /**
    * [[ExecuteResult]], if execution failed.
    *
    * @param name
    * @param message
    * @param stackTrace
    */
  final case class Error(
    name: String,
    message: String,
    stackTrace: List[String]
  ) extends ExecuteResult(success = false)

  object Error {
    def apply(msg: String): Error =
      Error("", msg, Nil)
  }

  /**
    * [[ExecuteResult]], if execution was aborted.
    */
  case object Abort extends ExecuteResult(success = false)


  /**
    * [[ExecuteResult]], if execution was exited
    */
  case object Exit extends ExecuteResult(success = true)
}
