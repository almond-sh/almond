package almond.echo

import java.util.Properties

import almond.interpreter.{Completion, ExecuteResult, Inspection, Interpreter}
import almond.interpreter.api.{DisplayData, OutputHandler}
import almond.interpreter.input.InputManager
import almond.protocol.KernelInfo
import java.nio.charset.StandardCharsets
import almond.protocol.RawJson

final class EchoInterpreter extends Interpreter {

  def kernelInfo(): KernelInfo =
    KernelInfo(
      "echo",
      EchoInterpreter.version,
      KernelInfo.LanguageInfo(
        "echo",
        "1.0",
        "text/echo",
        "echo",
        "text" // ???
      ),
      s"""Echo kernel ${EchoInterpreter.version}
         |Java ${sys.props.getOrElse("java.version", "[unknown]")}""".stripMargin
    )

  @volatile private var count = 0

  def execute(
    code: String,
    storeHistory: Boolean,
    inputManager: Option[InputManager],
    outputHandler: Option[OutputHandler]
  ): ExecuteResult =
    if (code.startsWith("print "))
      outputHandler match {
        case None =>
          ExecuteResult.Error("No output handler found")
        case Some(handler) =>
          handler.stdout(code.stripPrefix("print "))
          if (storeHistory)
            count += 1
          ExecuteResult.Success()
      }
    else {
      if (storeHistory)
        count += 1
      ExecuteResult.Success(
        DisplayData.text("> " + code)
      )
    }

  def currentLine(): Int =
    count

  override def complete(code: String, pos: Int): Completion = {

    val firstWord = code.takeWhile(_.isLetter)

    // try to complete 'print' at the beginning of the cell
    val completePrint = pos <= firstWord.length &&
      firstWord.length < "print".length &&
      "print".take(firstWord.length) == firstWord &&
      code.lift(firstWord.length).forall(_.isSpaceChar)

    if (completePrint)
      Completion(0, firstWord.length, Seq("print"))
    else if (code.startsWith("meta:") && pos == "meta:".length)
      Completion(
        pos,
        pos,
        Seq("sent"),
        None,
        RawJson(code.drop("meta:".length).getBytes(StandardCharsets.UTF_8))
      )
    else
      Completion.empty(pos)
  }

  override def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    if (
      code.startsWith("print") &&
      code.lift("print".length).forall(_.isSpaceChar) &&
      pos <= "print".length
    ) {
      val data = DisplayData.text(
        s"""${Console.RED}${Console.BOLD}print${Console.RESET}
           |
           |detail level: ${Console.BLUE}${Console.BOLD}$detailLevel${Console.RESET}""".stripMargin
      )
      Some(Inspection.fromDisplayData(data))
    }
    else
      None

}

object EchoInterpreter {

  lazy val version = {

    val p = new Properties

    try
      p.load(
        getClass
          .getClassLoader
          .getResourceAsStream("almond/echo.properties")
      )
    catch {
      case _: NullPointerException =>
    }

    Option(p.getProperty("version"))
      .getOrElse("[unknown]")
  }

}
