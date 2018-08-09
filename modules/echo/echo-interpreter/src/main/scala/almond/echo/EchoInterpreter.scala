package almond.echo

import almond.interpreter.{ExecuteResult, Interpreter, Message}
import almond.interpreter.api.{DisplayData, OutputHandler}
import almond.interpreter.input.InputManager
import almond.protocol.KernelInfo

final class EchoInterpreter extends Interpreter {

  def kernelInfo(): KernelInfo =
    KernelInfo(
      "echo",
      "0.1",
      KernelInfo.LanguageInfo(
        "echo",
        "1.0",
        "text/echo",
        "echo",
        "text" // ???
      ),
      "Echo kernel"
    )

  @volatile private var count = 0

  def execute(
    code: String,
    outputHandler: Option[OutputHandler],
    inputManager: Option[InputManager],
    storeHistory: Boolean,
    currentMessageOpt: Option[Message[_]]
  ): ExecuteResult =
    if (code.startsWith("print "))
      outputHandler match {
        case None =>
          ExecuteResult.Error("No output handler found")
        case Some(handler) =>
          handler.stdout(code.stripPrefix("print "))
          count += 1
          ExecuteResult.Success()
      }
    else {
      count += 1
      ExecuteResult.Success(
        DisplayData.text("> " + code)
      )
    }

  def currentLine(): Int =
    count

}
