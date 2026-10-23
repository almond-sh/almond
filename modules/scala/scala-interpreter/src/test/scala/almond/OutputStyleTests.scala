package almond

import almond.amm.PythonOutputStyle
import almond.directives.KernelOptions
import almond.interpreter.api.{DisplayData, ExecuteResult}
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import utest._

object OutputStyleTests extends TestSuite {
  private def interpreter(style: OutputStyle = OutputStyle.Python) =
    new ScalaInterpreter(
      params = interpreterParams.copy(outputStyle = style),
      logCtx = logCtx
    )

  private def text(value: String) = ExecuteResult.Success(DisplayData.text(value))
  private val empty               = ExecuteResult.Success(DisplayData())

  val tests = Tests {
    test("default output is unchanged") {
      val i      = interpreter(OutputStyle.Default)
      val result = noCrLf(i.execute("val first = 1; val second = 2"))
      assert(result == text("first: Int = 1\nsecond: Int = 2"))
    }
    test("definitions print nothing") {
      val i = interpreter()
      assert(i.execute("val alpha = 1234\nval beta = \"a-value\"") == empty)
      assert(i.execute("case class Record(alpha: Int, beta: Int)") == empty)
      assert(i.execute("import scala.collection.mutable; def f = 2; object O") == empty)
      assert(i.execute("var v = 1; lazy val l = sys.error(\"unused\")") == empty)
      assert(i.execute("val (first, second) = (1, 2)") == empty)
    }
    test("definitions remain available") {
      val i = interpreter()
      assert(i.execute("val first = 1; val second = first + 1") == empty)
      assert(i.execute("first + second") == text("3"))
    }
    test("only the last expression is printed") {
      val i = interpreter()
      assert(i.execute("1 + 1\n2 + 2 // last value\n// trailing comment\n") == text("4"))
      assert(i.execute("val a = 1\na + 1\na + 2") == text("3"))
    }
    test("expressions followed by definitions print nothing") {
      val i = interpreter()
      assert(i.execute("1 + 1\nval answer = 42") == empty)
      assert(i.execute("2 + 2\nimport scala.collection.mutable") == empty)
    }
    test("values only") {
      val i      = interpreter()
      val result = noCrLf(i.execute("Seq(\"a\", \"b\").map(_.toUpperCase)"))
      assert(result == text("List(\"A\", \"B\")"))
    }
    test("trailing semicolon") {
      val i = interpreter()
      assert(i.execute("1 + 1;") == empty)
      assert(i.execute("1 + 1; // no output") == empty)
      assert(i.execute("1 + 1; /* no output */\n") == empty)
      assert(i.execute("2 + 2 // not silenced;") == text("4"))
      assert(i.execute("\"a;\"") == text("\"a;\""))
    }
    test("unit") {
      val i              = interpreter()
      val capturedOutput = new StringBuilder
      val output = new MockOutputHandler {
        override def stdout(s: String): Unit = { capturedOutput.append(s); () }
      }
      val result = i.execute("println(\"hello\")", outputHandler = Some(output))
      assert(result == empty)
      assert(capturedOutput.toString.contains("hello"))
    }
    test("rich displays") {
      val i      = interpreter()
      val output = new MockOutputHandler
      val result = i.execute(
        """val first = almond.display.Html("<b>hidden</b>")
          |almond.display.Html("<b>last</b>")
          |""".stripMargin,
        outputHandler = Some(output)
      )
      assert(result == empty)
      val html = output.displayed().flatMap(_.detailedData.get("text/html").flatMap(_.asString))
      assert(html == Seq("<b>last</b>"))
    }
    test("explicit style takes precedence over lastValueOnly") {
      val i = new ScalaInterpreter(
        params = interpreterParams.copy(lastValueOnly = true, outputStyle = OutputStyle.Python),
        logCtx = logCtx
      )
      assert(i.execute("val a = 1; a + 1") == text("2"))
      assert(i.execute("val b = 2") == empty)
    }
    test("using directive") {
      val i = interpreter(OutputStyle.Default)
      assert(i.execute("//> using outputStyle python\nval a = 1") == empty)
      assert(i.execute("a + 1") == text("2"))
      assert(
        i.execute("//> using outputStyle last\nval b = a + 1; val c = b + 1") == text("c: Int = 3")
      )
      assert(i.execute("val d = 4; d + 1") == text("res4_1: Int = 5"))
      assert(i.execute("//> using outputStyle python") == empty)
      assert(i.execute("val e = 5; e + 1") == text("6"))
      assert(i.execute("//> using outputStyle default") == empty)
      assert(noCrLf(i.execute("val f = 6; val g = 7")) == text("f: Int = 6\ng: Int = 7"))
    }
    test("invalid directive value") {
      val i = interpreter(OutputStyle.Default)
      assert(!i.execute("//> using outputStyle foo\nval a = 1").success)
      assert(i.execute("val b = 2") == text("b: Int = 2"))
    }
    test("kernel options JSON") {
      val options = KernelOptions.AsJson(outputStyle = Some("python")).toKernelOptions
      assert(options == Right(KernelOptions(outputStyle = Some(OutputStyle.Python))))
      val asJson = KernelOptions.AsJson(KernelOptions(outputStyle = Some(OutputStyle.Last)))
      assert(asJson.outputStyle == Some("last"))
      assert(KernelOptions.AsJson(outputStyle = Some("foo")).toKernelOptions.isLeft)
    }
    test("upfront kernel options") {
      val i = new ScalaInterpreter(
        params = interpreterParams.copy(
          upfrontKernelOptions = KernelOptions(outputStyle = Some(OutputStyle.Python))
        ),
        logCtx = logCtx
      )
      assert(i.execute("val a = 1") == empty)
      assert(i.execute("a + 1") == text("2"))
    }
    test("variable inspector keeps definitions") {
      val i = interpreter()
      assert(i.execute("kernel.VariableInspector.init()").success)
      assert(i.execute("val first = 1; val second = 2") == empty)
      val output = new MockOutputHandler
      assert(i.execute(
        "kernel.VariableInspector.dictList()",
        outputHandler = Some(output)
      ).success)
      val data =
        output.displayed().flatMap(_.detailedData.get("text/plain").flatMap(_.asString)).mkString
      assert(data.contains("\"varName\":\"first\""), data.contains("\"varName\":\"second\""))
    }
    test("ends with semicolon") {
      def check(code: String, expected: Boolean) = {
        val res = PythonOutputStyle.endsWithSemicolon(code)
        assert(res == expected)
      }
      check("a;", true)
      check("a; \n  ", true)
      check("a; // comment", true)
      check("a; /* comment */", true)
      check("a; /* nested /* comment */ ; */", true)
      check("a // comment;", false)
      check("a /* comment; */", false)
      check("a", false)
      check("\"a;\"", false)
      check("\"a // b\";", true)
      check("\"\"\"a\n;\"\"\"", false)
      check("\"\"\"a\"\"\"\";", true)
      check("';'", false)
      check("'\"';", true)
      check("'\\'';", true)
    }
  }
}
