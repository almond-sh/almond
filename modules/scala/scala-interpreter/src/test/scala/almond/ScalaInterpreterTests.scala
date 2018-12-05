package almond

import almond.interpreter.api.DisplayData
import almond.interpreter.{Completion, ExecuteResult, Interpreter}
import ammonite.util.Colors
import utest._

object ScalaInterpreterTests extends TestSuite {

  private val interpreter: Interpreter =
    new ScalaInterpreter(
      initialColors = Colors.BlackWhite
    )

  val tests = Tests {

    "execute" - {

      // Code running is tested in (much) more detail in Ammonite itself.
      // We just test that things are wired up correctly here.

      "value" - {
        val code = "val n = 2"
        val res = interpreter.execute(code)
        val expectedRes = ExecuteResult.Success(DisplayData.text("n: Int = 2"))
        assert(res == expectedRes)
      }

      "exception" - {
        val code = """sys.error("foo")"""
        val res = interpreter.execute(code)
        assert(res.asError.exists(_.message.contains("java.lang.RuntimeException: foo")))
      }
    }

    "completion" - {

      // Completions are tested in more detail in Ammonite too.
      // Compared to it, we filter out stuff that contains '$', and pay
      // particular attention to the position parameter that it returns
      // (the Jupyter UI will replace some of the user code with a completion
      // using that parameter).

      * - {
        val code = "Lis"
        val res = interpreter.complete(code, code.length)
        val expectedRes = Completion(0, 3, Seq("List"))
        assert(res == expectedRes)
      }

      * - {
        val code = "HashM"
        val res = interpreter.complete(code, code.length)
        val expectedRes = Completion(
          0,
          5,
          Seq(
            "java.util.HashMap",
            "scala.collection.immutable.HashMap",
            "scala.collection.mutable.HashMap",
            "scala.collection.parallel.immutable.HashMapCombiner"
          )
        )
        assert(res == expectedRes)
      }

    }

    "predef" - {

      "simple" - {
        val predef = "val n = 2"
        val interp = new ScalaInterpreter(
          initialColors = Colors.BlackWhite,
          predefCode = predef
        )

        val res = interp.execute("val m = 2 * n")
        val expectedRes = ExecuteResult.Success(DisplayData.text("m: Int = 4"))
        assert(res == expectedRes)
      }

      "no variable name" - {
        val predef =
          """println("foo") // automatically generated: val res… = println("foo")
            |val n = 2
          """.stripMargin
        val interp = new ScalaInterpreter(
          initialColors = Colors.BlackWhite,
          predefCode = predef
        )

        val res = interp.execute("val m = 2 * n")
        val expectedRes = ExecuteResult.Success(DisplayData.text("m: Int = 4"))
        assert(res == expectedRes)
      }

      "compilation error" - {
        val predef = "val n = 2z"
        val interp = new ScalaInterpreter(
          initialColors = Colors.BlackWhite,
          predefCode = predef
        )

        val res =
          try {
            interp.execute("val m = 2 * n")
            false
          } catch {
            case e: ScalaInterpreter.PredefException =>
              assert(e.getCause == null)
              true
          }

        assert(res)
      }

      "exception" - {
        val predef = """val n: Int = sys.error("foo")"""
        val interp = new ScalaInterpreter(
          initialColors = Colors.BlackWhite,
          predefCode = predef
        )

        val res =
          try {
            interp.execute("val m = 2 * n")
            false
          } catch {
            case e: ScalaInterpreter.PredefException =>
              val msgOpt = Option(e.getCause).flatMap(e0 => Option(e0.getMessage))
              assert(msgOpt.contains("foo"))
              true
          }

        assert(res)
      }

    }

  }

}
