package jupyter.scala

import ammonite.pprint
import ammonite.shell.util.ColorSet
import ammonite.shell.Checker
import jupyter.kernel.interpreter.Interpreter
import jupyter.scala.config.ScalaKernel

import utest._

class InterpreterChecker(intp: Interpreter) extends Checker {

  var allOutput = ""

  var captureOut = false

  def session(sess: String): Unit = {
    val margin = sess.lines.filter(_.trim != "").map(_.takeWhile(' '.==).length).min
    val steps = sess.replace("\n" + margin, "\n").split("\n\n")

    for (step <- steps) {
      val (cmdLines, resultLines) = step.lines.map(_ drop margin).partition(_ startsWith "@ ")
      val commandText = cmdLines.map(_ stripPrefix "@ ").toVector

      val expected = resultLines.mkString("\n").trim

      var inc = ""
      for (line <- commandText.init) {
        allOutput += "\n@ " + line
        val (res, _) = run(inc + line)

        if (!line.startsWith("//"))
          failLoudly(assert(res == Interpreter.Incomplete))

        inc += line + "\n"
      }

      if (expected startsWith "error: ")
        fail(inc + commandText.last, _ contains expected.stripPrefix("error: "))
      else
        apply(inc + commandText.last, if (expected.isEmpty) null else expected)
    }
  }

  var buffer = ""

  def apply(input: String, expected: String = null) = {
    val (_, printed) = run(input)
    if (expected != null)
      failLoudly(assert(printed == expected.trim))
  }

  def run(input: String): (Interpreter.Result, String) = {
    val msg = collection.mutable.Buffer.empty[String]
    val f = { (s: String) => msg.synchronized(msg.append(s)) }
    val res = intp.interpret(buffer + input, Some(f, f), storeHistory = true)

    res match {
      case Interpreter.Value(d) =>
        for (s <- d.data.collectFirst{ case ("text/plain", s) => s }) {
          msg append s
        }

      case _ =>
    }

    val msgs = msg.mkString
    allOutput += msgs + "\n"
    (res, msgs)
  }

  def fail(input: String, failureCheck: String => Boolean = _ => true): Unit = {
    val (res, printed) = run(input)

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
        ScalaKernel.startJars,
        ScalaKernel.startDirs,
        Nil,
        identity,
        ScalaKernel.resolvers,
        Thread.currentThread().getContextClassLoader,
        pprintConfig = pprint.Config.Defaults.PPrintConfig,
        colors = ColorSet.BlackWhite
      )
    })
}