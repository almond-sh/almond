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

  }

}
