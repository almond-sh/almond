package jupyter.scala

import ammonite.api.{InterpreterError, Evaluated}
import ammonite.interpreter.Colors
import ammonite.shell.Checker
import jupyter.kernel.interpreter
import jupyter.kernel.interpreter.Interpreter

import utest._

class InterpreterChecker(intp: Interpreter) extends Checker {

  var allOutput = ""

  var captureOut = false

  def session(sess: String, captureOut: Boolean): Unit = {
    val margin = sess.lines.filter(_.trim != "").map(_.takeWhile(' '.==).length).min
    val steps = sess.replace("\n" + margin, "\n").split("\n\n")

    for (step <- steps) {
      val (cmdLines, resultLines) = step.lines.map(_ drop margin).partition(_ startsWith "@ ")
      val commandText = cmdLines.map(_ stripPrefix "@ ").toVector

      val expected = resultLines.mkString("\n").trim

      allOutput += commandText.map("\n@ " + _).mkString("\n")

      def filtered(err: String) = {
        err.replaceAll(" *\n", "\n").replaceAll("(?m)^Main\\Q.\\Escala:[0-9]*:", "Main.scala:*:")
      }

      if (expected startsWith "error: ")
        fail(commandText.mkString("\n"), {
          err =>
            val got = filtered(err.replaceAll("\u001B\\[[;\\d]*m", ""))
            val exp = filtered(expected.stripPrefix("error: "))
            val res = got contains exp
            if (!res) {
              val _got = got.split('\n').toList
              val _exp = exp.split('\n').toList
              println((_got zip _exp).map{case (g, e) => (g, e, g == e)})
              println(s"Got:\n$got\nExpected:\n$exp\n")
            }
            res
        })
      else
        apply(commandText.mkString("\n"), if (expected.isEmpty) null else expected)
    }
  }

  var buffer = ""

  def run0(input: String): (Interpreter.Result, String) = {
    val msg = collection.mutable.Buffer.empty[String]
    val f = { (s: String) => msg.synchronized(msg.append(s)) }
    buffer =
      if (buffer.isEmpty) input
      else buffer + "\n" + input
    val res = intp.interpret(buffer, Some(f, f), storeHistory = true, None)

    res match {
      case Interpreter.Value(d) =>
        for (s <- d.data.collectFirst{ case ("text/plain", s) => s }) {
          msg append s
        }

      case _ =>
    }

    if (res != Interpreter.Incomplete)
      buffer = ""

    val msgs = msg.mkString
    allOutput += msgs
    (res, msgs)
  }

  def run(input: String, captureOut: Boolean): (Either[InterpreterError, Evaluated[Unit]], Either[InterpreterError, String]) = {
    val (res0, output) = run0(input)

    res0 match {
      case e: interpreter.Interpreter.Error =>
        val ex = InterpreterError.UserException(new Exception(e.message))
        (Left(ex), Left(ex))
      case interpreter.Interpreter.Value(v) =>
        (Right(Evaluated("", Nil, ())), Right(output))
    }
  }

  def fail(input: String, failureCheck: String => Boolean = _ => true): Unit = {
    val (res, printed) = run0(input)

    res match{
      case Interpreter.Error(err) =>
        failLoudly(assert(failureCheck(err)))
      case e: Interpreter.Exception =>
        failLoudly(assert(failureCheck(e.traceBack mkString "\n")))
      case _ => assert({res; allOutput; false})
    }
  }

  def complete(cursor: Int, buf: String): (Int, Seq[String], Seq[String]) = {
    val (pos, res) = intp.complete(buf, cursor)
    (pos, res, Nil)
  }

  def failLoudly[T](t: => T) =
    try t
    catch {
      case e: utest.AssertionError =>
        println("FAILURE TRACE\n" + allOutput)
        throw e
    }

}

object ScalaInterpreterChecker {
  def apply(): InterpreterChecker =
    new InterpreterChecker({
      ScalaInterpreter(
        pprintConfig = pprint.Config.Defaults.PPrintConfig.copy(width = 80, height = 20),
        colors = Colors.BlackWhite,
        filterUnitResults = false
      )
    })
}
