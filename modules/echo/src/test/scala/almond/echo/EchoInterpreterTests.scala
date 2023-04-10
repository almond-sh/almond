package almond.echo

import almond.interpreter.{Completion, ExecuteResult, Interpreter, TestOutputHandler}
import almond.interpreter.api.DisplayData
import utest._

object EchoInterpreterTests extends TestSuite {

  val tests = Tests {

    test("simple") {

      val interpreter: Interpreter = new EchoInterpreter

      val res = interpreter.execute("foo")

      val textOutputOpt      = res.asSuccess.flatMap(_.data.data.get(DisplayData.ContentType.text))
      val expectedTextOutput = Option("> foo")

      assert {
        res // printed if the assertion is false
        textOutputOpt == expectedTextOutput
      }
    }

    test("print") {

      val interpreter: Interpreter = new EchoInterpreter
      val outputHandler            = new TestOutputHandler

      val res         = interpreter.execute("print foo", outputHandler = Some(outputHandler))
      val expectedRes = ExecuteResult.Success()
      assert(res == expectedRes)

      val output = outputHandler.result()
      val expectedOutput = Seq(
        TestOutputHandler.Output.Stdout("foo")
      )
      assert(output == expectedOutput)
    }

    test("complete") {

      val interpreter: Interpreter = new EchoInterpreter

      test("none") {
        val res         = interpreter.complete("zpri")
        val expectedRes = Completion(4, 4, Nil)
        assert(res == expectedRes)
      }

      test {
        val res         = interpreter.complete("pri")
        val expectedRes = Completion(0, 3, Seq("print"))
        assert(res == expectedRes)
      }

      test {
        val res         = interpreter.complete("pri", 0)
        val expectedRes = Completion(0, 3, Seq("print"))
        assert(res == expectedRes)
      }

      test {
        val res         = interpreter.complete("pri", 1)
        val expectedRes = Completion(0, 3, Seq("print"))
        assert(res == expectedRes)
      }

      test {
        val res         = interpreter.complete("pri foo", 1)
        val expectedRes = Completion(0, 3, Seq("print"))
        assert(res == expectedRes)
      }
    }

    test("inspect") {

      val interpreter: Interpreter = new EchoInterpreter

      test("none") {
        test {
          val res = interpreter.inspect("foo", 2)
          assert(res.isEmpty)
        }

        test {
          val res = interpreter.inspect("print foo", 7)
          assert(res.isEmpty)
        }
      }

      test("print") {
        test {
          val res = interpreter.inspect("print foo", 0)
          assert(res.nonEmpty)
        }

        test {
          val res = interpreter.inspect("print foo", 2)
          assert(res.nonEmpty)
        }

        test {
          val res = interpreter.inspect("print foo", "print".length)
          assert(res.nonEmpty)
        }
      }
    }

  }

}
