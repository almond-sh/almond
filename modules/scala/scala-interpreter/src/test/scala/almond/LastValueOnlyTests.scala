package almond

import almond.interpreter.api.{DisplayData, ExecuteResult}
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import utest._

object LastValueOnlyTests extends TestSuite {
  private def interpreter(enabled: Boolean = true) = new ScalaInterpreter(
    params = interpreterParams.copy(lastValueOnly = enabled),
    logCtx = logCtx
  )

  private def text(value: String) = ExecuteResult.Success(DisplayData.text(value))

  val tests = Tests {
    test("default output is unchanged") {
      val i      = interpreter(enabled = false)
      val result = noCrLf(i.execute("val first = 1; val second = 2"))
      assert(result == text("first: Int = 1\nsecond: Int = 2"))
    }
    test("definitions remain available") {
      val i      = interpreter()
      val result = i.execute("val first = 1; val second = first + 1")
      assert(result == text("second: Int = 2"))
      val next = i.execute("val total = first + second")
      assert(next == text("total: Int = 3"))
    }
    test("expressions and trailing comments") {
      val i      = interpreter()
      val result = i.execute("1 + 1\n2 + 2 // last value\n// trailing comment\n")
      assert(result == text("res1_1: Int = 4"))
    }
    test("imports and definitions do not hide the last value") {
      val i      = interpreter()
      val result = i.execute("val answer = 42; import scala.collection.mutable; def f = answer")
      assert(result == text("answer: Int = 42"))
      val definitions = i.execute("import scala.collection.mutable; class C; def g = 1")
      assert(definitions == ExecuteResult.Success(DisplayData()))
    }
    test("unit does not redisplay an earlier value") {
      val i      = interpreter()
      val result = i.execute("val answer = 42; ()")
      assert(result == ExecuteResult.Success(DisplayData()))
    }
    test("destructuring") {
      val i      = interpreter()
      val result = i.execute("val (first, second) = (1, 2)")
      assert(result == text("second: Int = 2"))
    }
    test("intermediate values are not rendered") {
      val i = interpreter()
      val result = i.execute(
        """val first = new Object { override def toString = sys.error("must not render") }
          |val second = 2
          |""".stripMargin
      )
      assert(result == text("second: Int = 2"))
    }
    test("vars and lazy vals") {
      val i      = interpreter()
      val result = i.execute("var first = 1; lazy val unused = sys.error(\"unused\"); val last = 3")
      assert(result == text("last: Int = 3"))
      val next = i.execute("first += 1; val answer = first")
      assert(next == text("answer: Int = 2"))
    }
    test("explicit output and rich displays") {
      val i              = interpreter()
      val capturedOutput = new StringBuilder
      val output = new MockOutputHandler {
        override def stdout(s: String): Unit = { capturedOutput.append(s); () }
      }
      val result = i.execute(
        """println("hello")
          |almond.display.Html("<b>explicit</b>").display()
          |val first = almond.display.Html("<b>hidden</b>")
          |val last = almond.display.Html("<b>last</b>")
          |""".stripMargin,
        outputHandler = Some(output)
      )
      assert(result.success)
      assert(capturedOutput.toString.contains("hello"))
      val html = output.displayed().flatMap(_.detailedData.get("text/html").flatMap(_.asString))
      assert(html == Seq("<b>explicit</b>", "<b>last</b>"))
    }
    test("variable inspector keeps intermediate definitions") {
      if (isScala2) {
        val i = interpreter()
        assert(i.execute("kernel.VariableInspector.init()").success)
        assert(i.execute("val first = 1; val second = 2").success)
        val output = new MockOutputHandler
        assert(i.execute(
          "kernel.VariableInspector.dictList()",
          outputHandler = Some(output)
        ).success)
        val data =
          output.displayed().flatMap(_.detailedData.get("text/plain").flatMap(_.asString)).mkString
        assert(data.contains("\"varName\":\"first\""), data.contains("\"varName\":\"second\""))
      }
    }
  }
}
