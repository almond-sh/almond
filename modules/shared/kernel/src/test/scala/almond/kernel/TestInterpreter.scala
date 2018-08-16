package almond.kernel

import almond.interpreter.{ExecuteResult, Interpreter}
import almond.interpreter.api.{CommHandler, DisplayData, OutputHandler}
import almond.interpreter.comm.CommManager
import almond.interpreter.input.InputManager

import scala.concurrent.Await
import scala.concurrent.duration.Duration

final class TestInterpreter extends Interpreter {
  def execute(
    code: String,
    storeHistory: Boolean,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler]
  ): ExecuteResult =
    if (code.startsWith("input:"))
      inputManager match {
        case None =>
          ExecuteResult.Error("input not available")
        case Some(m) =>
          val s = Await.result(
            m.readInput(code.stripPrefix("input:")),
            Duration.Inf
          )
          count += 1
          ExecuteResult.Success(DisplayData.text("> " + s))
      }
    else if (code.startsWith("comm-open:"))
      commHandlerOpt0 match {
        case None =>
          ExecuteResult.Error("comm not available")
        case Some(h) =>
          val target = code.stripPrefix("comm-open:")
          h.commOpen(target, target, "{}")
          count += 1
          ExecuteResult.Success()
      }
    else if (code.startsWith("comm-message:"))
      commHandlerOpt0 match {
        case None =>
          ExecuteResult.Error("comm not available")
        case Some(h) =>
          val target = code.stripPrefix("comm-message:")
          h.commMessage(target, """{"a": "b"}""")
          count += 1
          ExecuteResult.Success()
      }
    else if (code.startsWith("comm-close:"))
      commHandlerOpt0 match {
        case None =>
          ExecuteResult.Error("comm not available")
        case Some(h) =>
          val target = code.stripPrefix("comm-close:")
          h.commClose(target, "{}")
          count += 1
          ExecuteResult.Success()
      }
    else if (code.startsWith("echo:")) {
      count += 1
      ExecuteResult.Success(DisplayData.text(code.stripPrefix("echo:")))
    } else
      ExecuteResult.Error("not input")

  private var count = 0
  def currentLine() = count

  def kernelInfo() = ???

  private val commManager = new CommManager
  private var commHandlerOpt0 = Option.empty[CommHandler]
  override def commManagerOpt = Some(commManager)
  override def setCommHandler(commHandler: CommHandler): Unit =
    commHandlerOpt0 = Some(commHandler)
}
