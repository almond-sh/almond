package almond

import java.nio.file.{Path, Paths}

import almond.interpreter.api.{DisplayData, ExecuteResult}
import almond.interpreter.{Completion, Interpreter, IsCompleteResult}
import almond.protocol.Codecs.stringCodec
import almond.protocol.RawJson
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import almond.amm.AmmInterpreter
import ammonite.util.Colors
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import coursierapi.{Dependency, Module}
import utest._

object ScalaInterpreterTests extends TestSuite {

  private val sbv = {
    val sv =
      if (scala.util.Properties.versionNumberString.startsWith("2."))
        scala.util.Properties.versionNumberString
      else
        "2.13.16"
    sv.split('.').take(2).mkString(".")
  }

  private def newInterpreter(): Interpreter =
    new ScalaInterpreter(
      params = interpreterParams.copy(
        automaticDependencies = Map(
          Module.of("org.scalacheck", "*") -> Seq(
            Dependency.of("com.github.alexarchambault", s"scalacheck-shapeless_1.14_$sbv", "1.2.3")
          )
        ),
        automaticVersions = Map(
          Module.of("org.scalacheck", s"scalacheck_$sbv") -> "1.14.0"
        )
      ),
      logCtx = logCtx
    )

  private val interpreter: Interpreter = newInterpreter()

  private object Predef {
    private def predefPath(name: String): Path =
      Paths.get(getClass.getResource(s"/test-predefs/$name.sc").toURI)

    def simple(fileBased: Boolean = false): Unit = {

      val (predefCode, predefFiles) =
        if (fileBased)
          ("", Seq(predefPath("simple")))
        else
          ("val n = 2", Nil)

      val interp = new ScalaInterpreter(
        params = interpreterParams.copy(
          predefCode = predefCode,
          predefFiles = predefFiles
        ),
        logCtx = logCtx
      )

      val res         = interp.execute("val m = 2 * n")
      val expectedRes = ExecuteResult.Success(DisplayData.text("m: Int = 4"))
      assert(res == expectedRes)
    }

    def noVariableName(fileBased: Boolean = false): Unit = {

      val (predefCode, predefFiles) =
        if (fileBased)
          ("", Seq(predefPath("no-variable-name")))
        else {
          val code =
            """println("foo") // automatically generated: val res… = println("foo")
              |val n = 2
            """.stripMargin
          (code, Nil)
        }
      val interp = new ScalaInterpreter(
        params = interpreterParams.copy(
          predefCode = predefCode,
          predefFiles = predefFiles
        ),
        logCtx = logCtx
      )

      val res         = interp.execute("val m = 2 * n")
      val expectedRes = ExecuteResult.Success(DisplayData.text("m: Int = 4"))
      assert(res == expectedRes)
    }

    def compilationError(fileBased: Boolean = false): Unit = {

      val (predefCode, predefFiles) =
        if (fileBased)
          ("", Seq(predefPath("compilation-error")))
        else
          ("val n = 2z", Nil)

      val interp = new ScalaInterpreter(
        params = interpreterParams.copy(
          predefCode = predefCode,
          predefFiles = predefFiles,
          lazyInit = true // predef throws here else
        ),
        logCtx = logCtx
      )

      val res =
        try {
          interp.execute("val m = 2 * n")
          false
        }
        catch {
          case e: AmmInterpreter.PredefException =>
            assert(e.getCause == null)
            true
        }

      assert(res)
    }

    def exception(fileBased: Boolean = false): Unit = {

      val (predefCode, predefFiles) =
        if (fileBased)
          ("", Seq(predefPath("exception")))
        else
          ("""val n: Int = sys.error("foo")""", Nil)
      val interp = new ScalaInterpreter(
        params = interpreterParams.copy(
          predefCode = predefCode,
          predefFiles = predefFiles,
          lazyInit = true // predef throws here else
        ),
        logCtx = logCtx
      )

      val res =
        try {
          interp.execute("val m = 2 * n")
          false
        }
        catch {
          case e: AmmInterpreter.PredefException =>
            val msgOpt = Option(e.getCause).flatMap(e0 => Option(e0.getMessage))
            assert(msgOpt.contains("foo"))
            true
        }

      assert(res)
    }
  }

  private implicit class TestCompletionOps(private val compl: Completion) extends AnyVal {
    def clearMetadata: Completion =
      compl.copy(
        metadata = RawJson.emptyObj
      )
  }

  val tests = Tests {

    test("execute") {

      // Code running is tested in (much) more detail in Ammonite itself.
      // We just test that things are wired up correctly here.

      test("value") {
        val code        = "val n = 2"
        val res         = interpreter.execute(code)
        val expectedRes = ExecuteResult.Success(DisplayData.text("n: Int = 2"))
        assert(res == expectedRes)
      }

      test("respect store history") {
        val interpreter = newInterpreter()
        val noHistoryTextOpt = interpreter.execute("2", storeHistory = false)
          .asSuccess
          .flatMap(_.data.detailedData.get("text/plain"))
          .flatMap(_.asString)
          .map(_.dropWhile(_ != ':'))
        val expectedNoHistoryTextOpt = Option(": Int = 2")
        assert(noHistoryTextOpt == expectedNoHistoryTextOpt)

        val textOpt = interpreter.execute("3")
          .asSuccess
          .flatMap(_.data.detailedData.get("text/plain"))
          .flatMap(_.asString)
        val expectedTextOpt = Option("res1: Int = 3")
        assert(textOpt == expectedTextOpt)
      }

      test("exception") {
        val code = """sys.error("foo\nbar")"""
        val res  = interpreter.execute(code)
        assert(res.asError.exists(_.name.contains("java.lang.RuntimeException")))
        assert(res.asError.exists(_.message.contains("foo\nbar")))
        assert(res.asError.exists(_.stackTrace.exists(_.contains("ammonite."))))
      }
    }

    test("completion") {

      // Completions are tested in more detail in Ammonite too.
      // Compared to it, we filter out stuff that contains '$', and pay
      // particular attention to the position parameter that it returns
      // (the Jupyter UI will replace some of the user code with a completion
      // using that parameter).

      test {
        val code        = "repl.la"
        val expectedRes = Completion(5, 7, Seq("lastException"))
        val res         = interpreter.complete(code, code.length).clearMetadata
        assert(res == expectedRes)
      }

      def listTest(): Unit = {
        val code                   = "Lis"
        val expectedRes            = Completion(0, 3, Seq("List"))
        val alternativeExpectedRes = Completion(0, 3, Seq("scala.List"))
        val res0                   = interpreter.complete(code, code.length).clearMetadata
        val res = res0.copy(
          completions = res0.completions.filter(expectedRes.completions.toSet)
        )
        val alternativeRes = res0.copy(
          completions = res0.completions.filter(alternativeExpectedRes.completions.toSet)
        )
        assert(res == expectedRes || alternativeRes == alternativeExpectedRes)
      }

      test {
        if (TestUtil.isScala2) listTest()
        else "disabled"
      }

      def hashMapTest(): Unit = {
        val code = "HashM"

        val extraCompletions =
          if (isScala212)
            Seq("scala.collection.parallel.immutable.HashMapCombiner")
          else
            Nil

        val expectedRes = Completion(
          0,
          5,
          Seq(
            "java.util.HashMap",
            "scala.collection.immutable.HashMap",
            "scala.collection.mutable.HashMap"
          ) ++ extraCompletions
        )
        val res0 = interpreter.complete(code, code.length).clearMetadata
        val res = res0.copy(
          completions = res0.completions.filter(expectedRes.completions.toSet)
        )
        assert(res == expectedRes)
      }

      test {
        if (TestUtil.isScala2) hashMapTest()
        else "disabled"
      }

      def backticksTest(): Unit = {
        // see https://github.com/almond-sh/almond/issues/628
        val interpreter = newInterpreter()
        val res = interpreter.execute(
          """val `a-b-c` = 2
            |val abcdef = 3""".stripMargin
        )
        assert(res.asSuccess.nonEmpty)

        def check(code: String, pos: Int, expectedRes: Completion): Unit = {
          val res = interpreter.complete(code, pos).clearMetadata
          assert(res == expectedRes)
        }

        // cursor before the closing backtick
        check("`a-b`", 4, Completion(1, 4, Seq("a-b-c")))
        check("List(`a-b`)", 9, Completion(6, 9, Seq("a-b-c")))
        check("`ab`", 3, Completion(1, 3, Seq("abcdef")))
        // cursor after the closing backtick
        check("`a-b`", 5, Completion(0, 5, Seq("`a-b-c`")))
        // no closing backtick
        check("`a-b", 4, Completion(0, 4, Seq("`a-b-c`")))
        check("`ab", 3, Completion(0, 3, Seq("`abcdef`")))
        // no backtick around the completed identifier
        check("abcd", 4, Completion(0, 4, Seq("abcdef")))
        check("`a-b-c`.toSt", 12, Completion(8, 12, Seq("toString")))
      }

      test("backticks") {
        // Scope completions crash in Ammonite 3.1.0's completion internals for Scala >= 3.9
        if (scala.util.Properties.versionNumberString.startsWith("3.9.")) "disabled"
        else backticksTest()
      }

    }

    test("inspection") {
      def html(interpreter: Interpreter, code: String, pos: Int): String =
        interpreter.inspect(code, pos, detailLevel = 1)
          .flatMap(_.data.get("text/html"))
          .map(raw => readFromArray(raw.value)(stringCodec))
          .getOrElse(sys.error(s"No HTML inspection result for '$code' at $pos"))

      // No source JAR gets indexed in these tests, so no scaladoc is ever found,
      // and the inspection results are only made of the type of the tree.
      def expected(typeStr: String): String =
        s"<div><pre>$typeStr</pre></div>"

      test("tree shapes") {
        if (TestUtil.isScala2) {
          val interpreter = newInterpreter()

          val defCode = "def increment(n: Int): Int = n + 1"
          val defRes  = html(interpreter, defCode, defCode.indexOf("increment") + 1)
          val expectedDefRes =
            if (isScala212) expected("(n: Int)Int")
            else expected("(n: Int): Int")
          assert(defRes == expectedDefRes)

          val inferredValCode = "val message = List(1, 2, 3).mkString"
          val inferredValRes =
            html(interpreter, inferredValCode, inferredValCode.indexOf("message") + 1)
          assert(inferredValRes == expected("String"))

          val typedValCode = "val count: Long = 2L"
          val typedValRes  = html(interpreter, typedValCode, typedValCode.indexOf("count") + 1)
          assert(typedValRes == expected("Long"))
        }
        else
          "disabled"
      }

      test("import tree fallback") {
        if (TestUtil.isScala2) {
          val interpreter = newInterpreter()
          val code        = "import scala.collection.mutable.ArrayBuffer"
          val pos         = code.indexOf("mutable") + 1

          // in particular, none of those should be "<unknown>"
          val expectedRes = expected("import scala.collection.mutable.ArrayBuffer")
          val results     = (1 to 25).map(_ => html(interpreter, code, pos))
          assert(results.forall(_ == expectedRes))
        }
        else
          "disabled"
      }

      test("incomplete member selection") {
        if (TestUtil.isScala2) {
          val interpreter = newInterpreter()

          // the qualifier is inspected, rather than the erroneous selection
          // (see https://github.com/almond-sh/almond/issues/1447)
          assert(html(interpreter, "1.", 2) == expected("Int"))
          assert(html(interpreter, "\"foo\".", 6) == expected("String"))
          assert(html(interpreter, "List(1, 2).", 11) == expected("List[Int]"))
        }
        else
          "disabled"
      }

      test("erroneous tree") {
        if (TestUtil.isScala2) {
          val interpreter = newInterpreter()
          val code        = "thisIsNotDefined."
          val res         = interpreter.inspect(code, code.length, detailLevel = 0)
          assert(res.isEmpty)
        }
        else
          "disabled"
      }

      test("constructor and inherited documentation") {
        if (TestUtil.isScala2) {
          val interpreter = newInterpreter()

          val constructorCode = "new java.lang.String(Array[Byte](65))"
          val constructorHtml = html(
            interpreter,
            constructorCode,
            constructorCode.indexOf("String") + 1
          )
          assert(constructorHtml == expected("String"))

          val inheritedCode = "List(1, 2, 3).isEmpty"
          val inheritedHtml = html(
            interpreter,
            inheritedCode,
            inheritedCode.indexOf("isEmpty") + 1
          )
          assert(inheritedHtml == expected("Boolean"))
        }
        else
          "disabled"
      }
    }

    test("predef code") {
      test("simple") {
        Predef.simple()
      }
      test("no variable name") {
        Predef.noVariableName()
      }
      test("compilation error") {
        if (TestUtil.isScala2) Predef.compilationError()
        else "Temporarily disabled in Scala 3"
      }
      test("exception") {
        Predef.exception()
      }
    }

    test("predef files") {
      test("simple") {
        Predef.simple(fileBased = true)
      }
      test("no variable name") {
        Predef.noVariableName(fileBased = true)
      }
      test("compilation error") {
        if (TestUtil.isScala2) Predef.compilationError(fileBased = true)
        else "Temporarily disabled in Scala 3"
      }
      test("exception") {
        Predef.exception(fileBased = true)
      }
    }

    test("silent") {
      test("defaults false") {
        val code        = "val silent = kernel.silent"
        val res         = newInterpreter().execute(code)
        val expectedRes = ExecuteResult.Success(DisplayData.text("silent: Boolean = false"))
        assert(res == expectedRes)
      }
      test("can be set to true") {
        val code =
          """
            | val silentBefore = kernel.silent
            | kernel.silent(true)
            | val silentAfter = kernel.silent
            |""".stripMargin
        val res = newInterpreter().execute(code)
        val expectedRes = ExecuteResult.Success(DisplayData.text(
          """silentBefore: Boolean = false
            |silentAfter: Boolean = true""".stripMargin
        ))
        assert(TestUtil.noCrLf(res) == TestUtil.noCrLf(expectedRes))
      }
      test("can be set to false") {
        val code =
          """
            | kernel.silent(true)
            | val silentBefore = kernel.silent
            | kernel.silent(false)
            | val silentAfter = kernel.silent
            |""".stripMargin
        val res = newInterpreter().execute(code)
        val expectedRes = ExecuteResult.Success(DisplayData.text(
          """silentBefore: Boolean = true
            |silentAfter: Boolean = false""".stripMargin
        ))
        assert(TestUtil.noCrLf(res) == TestUtil.noCrLf(expectedRes))
      }
      test("affects subsequent calls to execute when enabled") {
        val code0 =
          """
            | kernel.silent(true)
            | val noEffectInSameExecute = kernel.silent
            |""".stripMargin
        val code1 =
          """
            | val effectInNextExecute = 0
            |""".stripMargin
        val code2 =
          """
            | val effectInNextExecuteAgain = 0
            |""".stripMargin
        val i    = newInterpreter()
        val res0 = i.execute(code0)
        val res1 = i.execute(code1)
        val res2 = i.execute(code2)
        val expectedRes0 = ExecuteResult.Success(DisplayData.text(
          "noEffectInSameExecute: Boolean = true"
        ))
        val expectedRes1 = ExecuteResult.Success(DisplayData.empty)
        val expectedRes2 = ExecuteResult.Success(DisplayData.empty)
        assert(res0 == expectedRes0)
        assert(res1 == expectedRes1)
        assert(res2 == expectedRes2)
      }

      test("affects subsequent calls to execute when disabled") {
        val code0 = "kernel.silent(true)"
        val code1 =
          """
            | kernel.silent(false)
            | val noEffectInSameExecute = kernel.silent
            |""".stripMargin
        val code2 =
          """
            | val effectInNextExecute = kernel.silent
            |""".stripMargin
        val code3 =
          """
            | val effectInNextExecuteAgain = kernel.silent
            |""".stripMargin

        val i    = newInterpreter()
        val res0 = i.execute(code0)
        val res1 = i.execute(code1)
        val res2 = i.execute(code2)
        val res4 = i.execute(code3)

        val expectedRes0 = ExecuteResult.Success(DisplayData.empty)
        val expectedRes1 = ExecuteResult.Success(DisplayData.empty)
        val expectedRes2 = ExecuteResult.Success(DisplayData.text(
          "effectInNextExecute: Boolean = false"
        ))
        val expectedRes3 = ExecuteResult.Success(DisplayData.text(
          "effectInNextExecuteAgain: Boolean = false"
        ))

        assert(res0 == expectedRes0)
        assert(res1 == expectedRes1)
        assert(res2 == expectedRes2)
        assert(res4 == expectedRes3)
      }
    }

    test("inspection") {
      if (TestUtil.isScala2) {
        val code          = "List"
        val inspectionOpt = interpreter.inspect(code, code.length, detailLevel = 0)
        val data          = inspectionOpt.toSeq.flatMap(_.data)

        assert(data.exists(_._1 == "text/html"))
        assert(data.exists(_._1 == "text/plain"))
      }
    }

    test("is complete") {
      def check(code: String, expected: IsCompleteResult): Unit = {
        val res = interpreter.isComplete(code)
        assert(res == Some(expected))
      }

      test("complete") {
        check("val n = 2", IsCompleteResult.Complete)
      }
      test("invalid") {
        check("val n = )", IsCompleteResult.Invalid)
      }
      test("incomplete") {
        test("new block") {
          check("def f(n: Int) = {", IsCompleteResult.Incomplete("  "))
        }
        test("nested block") {
          val code =
            """def f(n: Int) = {
              |  if (n > 0) {""".stripMargin
          check(code, IsCompleteResult.Incomplete("    "))
        }
        test("keep indentation") {
          val code =
            """def f(n: Int) = {
              |  val m = n + 1""".stripMargin
          check(code, IsCompleteResult.Incomplete("  "))
        }
        test("after closed block") {
          val code =
            """def f(n: Int) = {
              |  if (n > 0) {
              |    n
              |  }
              |""".stripMargin
          check(code, IsCompleteResult.Incomplete("  "))
        }
      }
    }

    test("dependencies") {
      test("auto dependency") {
        test("example") {
          if (TestUtil.isScala212) {
            val code =
              """import $ivy.`org.scalacheck::scalacheck:1.14.0`
                |import org.scalacheck.ScalacheckShapeless._
                |""".stripMargin
            val res = interpreter.execute(code)
            assert(res.asSuccess.nonEmpty)
          }
        }
      }

      test("auto version") {
        test("simple") {
          val code =
            """import $ivy.`org.scalacheck::scalacheck:_ compat`
              |import org.scalacheck.Arbitrary
              |""".stripMargin
          val res = interpreter.execute(code)
          assert(res.asSuccess.nonEmpty)
        }
      }
    }

    /** Name of the value printed last in a successful result, like "res12" */
    def resultName(res: ExecuteResult): String =
      res.asSuccess
        .flatMap(_.data.detailedData.get("text/plain"))
        .flatMap(_.asString)
        .flatMap(_.linesIterator.toList.lastOption)
        .getOrElse("")
        .takeWhile(_ != ':')

    def variableInspectorTest(): Unit = {

      implicit class ExecuteResultOps(private val res: ExecuteResult) {
        def assertSuccess(): ExecuteResult = {
          assert(res.asSuccess.nonEmpty)
          res
        }
      }

      implicit class TestDisplayDataOps(private val data: DisplayData) {
        def text: String =
          data.detailedData.get("text/plain").flatMap(_.asString).getOrElse("")
      }

      def initCode     = "_root_.almond.api.JupyterAPIHolder.value.VariableInspector.init()"
      def dictListCode = "_root_.almond.api.JupyterAPIHolder.value.VariableInspector.dictList()"

      val interpreter = newInterpreter()

      // defined before the variable inspector is enabled -> no variable inspector code gen,
      // so not in the variable listing
      interpreter.execute("val p = 1")
        .assertSuccess()

      interpreter.execute(initCode)
        .assertSuccess()

      val outputHandler = new MockOutputHandler

      interpreter.execute(dictListCode, outputHandler = Some(outputHandler))
        .assertSuccess()
      val Seq(before) = outputHandler.displayed()
      assert(before.text == "[]")

      interpreter.execute("val n = 2")
        .assertSuccess()

      // that inline JSON is kind of meh

      interpreter.execute(dictListCode, outputHandler = Some(outputHandler))
        .assertSuccess()
      val Seq(after) = outputHandler.displayed()
      assert(
        after.text == """[{"varName":"n","varSize":"","varShape":"","varContent":"2","varType":"Int","isMatrix":false}]"""
      )

      interpreter.execute("val m = true")
        .assertSuccess()

      interpreter.execute(dictListCode, outputHandler = Some(outputHandler))
        .assertSuccess()
      val Seq(after1) = outputHandler.displayed()
      assert(
        after1.text == """[{"varName":"n","varSize":"","varShape":"","varContent":"2","varType":"Int","isMatrix":false},{"varName":"m","varSize":"","varShape":"","varContent":"true","varType":"Boolean","isMatrix":false}]"""
      )

      interpreter.execute("val m = 4")
        .assertSuccess()

      interpreter.execute(dictListCode, outputHandler = Some(outputHandler))
        .assertSuccess()
      val Seq(after2) = outputHandler.displayed()
      assert(
        after2.text == """[{"varName":"n","varSize":"","varShape":"","varContent":"2","varType":"Int","isMatrix":false},{"varName":"m","varSize":"","varShape":"","varContent":"4","varType":"Int","isMatrix":false}]"""
      )

      interpreter.execute("import scala.collection.mutable")
        .assertSuccess()

      interpreter.execute("trait ATrait")
        .assertSuccess()

      interpreter.execute("class AClass")
        .assertSuccess()

      interpreter.execute("abstract class AbstractClass")
        .assertSuccess()

      interpreter.execute("object AnObject")
        .assertSuccess()

      interpreter.execute("case class CaseClass()")
        .assertSuccess()

      interpreter.execute("case object CaseObject")
        .assertSuccess()

      interpreter.execute("type Str = String")
        .assertSuccess()

      interpreter.execute(dictListCode, outputHandler = Some(outputHandler))
        .assertSuccess()
      val Seq(after3) = outputHandler.displayed()
      assert(after3.text == after2.text)

      interpreter.execute("lazy val lz = 5")
        .assertSuccess()
      interpreter.execute("def d = 6")
        .assertSuccess()
      interpreter.execute("def f(i: Int) = i")
        .assertSuccess()
      interpreter.execute("private val priv = 7")
        .assertSuccess()
      // name of the result, like "res12"
      val listResName = resultName(interpreter.execute("List(1, 2)").assertSuccess())
      interpreter.execute("println(\"foo\")")
        .assertSuccess()

      interpreter.execute(dictListCode, outputHandler = Some(outputHandler))
        .assertSuccess()
      val Seq(after4) = outputHandler.displayed()
      val expectedAfter4 = after2.text.stripSuffix("]") +
        """,{"varName":"lz","varSize":"","varShape":"","varContent":"[lazy]","varType":"Int","isMatrix":false}""" +
        """,{"varName":"d","varSize":"","varShape":"","varContent":"[def]","varType":"Int","isMatrix":false}""" +
        (if (TestUtil.isScala2)
           """,{"varName":"priv","varSize":"","varShape":"","varContent":"7","varType":"Int","isMatrix":false}"""
         else "") +
        s""",{"varName":"$listResName","varSize":"","varShape":"","varContent":"List(1, 2)","varType":"List[Int]","isMatrix":false}]"""
      assert(after4.text == expectedAfter4)
    }
    test("variable inspector") {
      variableInspectorTest()
    }
    test("variable inspector with several statements per cell") {
      val interpreter = newInterpreter()
      val initCode    = "_root_.almond.api.JupyterAPIHolder.value.VariableInspector.init()"
      assert(interpreter.execute(initCode).success)
      val res = interpreter.execute("val (a, b) = (1, \"foo\")\nval c = a + 1\nc * 2")
      assert(res.success)
      // like "res2_2"
      val resName = resultName(res)
      assert(resName.endsWith("_2"))
      val outputHandler = new MockOutputHandler
      val dictListCode  = "_root_.almond.api.JupyterAPIHolder.value.VariableInspector.dictList()"
      assert(interpreter.execute(dictListCode, outputHandler = Some(outputHandler)).success)
      val text = outputHandler.displayed()
        .flatMap(_.detailedData.get("text/plain").flatMap(_.asString))
        .mkString
      for (name <- Seq("a", "b", "c", resName))
        assert(text.contains(s""""varName":"$name""""))
    }
  }

}
