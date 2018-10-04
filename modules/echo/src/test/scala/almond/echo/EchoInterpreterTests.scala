package almond.echo

import almond.interpreter.{Completion, ExecuteResult, Interpreter, TestOutputHandler}
import almond.interpreter.api.DisplayData
import utest._

object EchoInterpreterTests extends TestSuite {

  val tests = Tests {

    'simple - {

      val interpreter: Interpreter = new EchoInterpreter

      val res = interpreter.execute("foo")

      val textOutputOpt = res.asSuccess.flatMap(_.data.data.get(DisplayData.ContentType.text))
      val expectedTextOutput = Option("> foo")

      assert {
        res // printed if the assertion is false
        textOutputOpt == expectedTextOutput
      }
    }

    'print - {

      val interpreter: Interpreter = new EchoInterpreter
      val outputHandler = new TestOutputHandler

      val res = interpreter.execute("print foo", outputHandler = Some(outputHandler))
      val expectedRes = ExecuteResult.Success()
      assert(res == expectedRes)

      val output = outputHandler.result()
      val expectedOutput = Seq(
        TestOutputHandler.Output.Stdout("foo")
      )
      assert(output == expectedOutput)
    }

    'complete - {

      val interpreter: Interpreter = new EchoInterpreter

      'none - {
        val res = interpreter.complete("zpri")
        val expectedRes = Completion(4, 4, Nil)
        assert(res == expectedRes)
      }

      * - {
        val res = interpreter.complete("pri")
        val expectedRes = Completion(0, 3, Seq("print"))
        assert(res == expectedRes)
      }

      * - {
        val res = interpreter.complete("pri", 0)
        val expectedRes = Completion(0, 3, Seq("print"))
        assert(res == expectedRes)
      }

      * - {
        val res = interpreter.complete("pri", 1)
        val expectedRes = Completion(0, 3, Seq("print"))
        assert(res == expectedRes)
      }

      * - {
        val res = interpreter.complete("pri foo", 1)
        val expectedRes = Completion(0, 3, Seq("print"))
        assert(res == expectedRes)
      }
    }

  }

}
