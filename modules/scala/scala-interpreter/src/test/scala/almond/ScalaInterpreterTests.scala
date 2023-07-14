package almond

import java.nio.file.{Path, Paths}

import almond.interpreter.api.DisplayData
import almond.interpreter.{Completion, ExecuteResult, Interpreter}
import almond.protocol.RawJson
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import almond.amm.AmmInterpreter
import ammonite.util.Colors
import coursierapi.{Dependency, Module}
import utest._

object ScalaInterpreterTests extends TestSuite {

  private val sbv = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  private def newInterpreter(): Interpreter =
    new ScalaInterpreter(
      params = ScalaInterpreterParams(
        initialColors = Colors.BlackWhite,
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
        params = ScalaInterpreterParams(
          predefCode = predefCode,
          predefFiles = predefFiles,
          initialColors = Colors.BlackWhite
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
        params = ScalaInterpreterParams(
          predefCode = predefCode,
          predefFiles = predefFiles,
          initialColors = Colors.BlackWhite
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
        params = ScalaInterpreterParams(
          predefCode = predefCode,
          predefFiles = predefFiles,
          initialColors = Colors.BlackWhite,
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
        params = ScalaInterpreterParams(
          predefCode = predefCode,
          predefFiles = predefFiles,
          initialColors = Colors.BlackWhite,
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
          .flatMap(_.data.data.get("text/plain"))
          .map(_.dropWhile(_ != ':'))
        val expectedNoHistoryTextOpt = Option(": Int = 2")
        assert(noHistoryTextOpt == expectedNoHistoryTextOpt)

        val textOpt = interpreter.execute("3")
          .asSuccess
          .flatMap(_.data.data.get("text/plain"))
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

    }

    test("predef code") {
      "simple" - Predef.simple()
      "no variable name" - Predef.noVariableName()
      test("compilation error") {
        if (TestUtil.isScala2) Predef.compilationError()
        else "Temporarily disabled in Scala 3"
      }
      "exception" - Predef.exception()
    }

    test("predef files") {
      "simple" - Predef.simple(fileBased = true)
      "no variable name" - Predef.noVariableName(fileBased = true)
      test("compilation error") {
        if (TestUtil.isScala2) Predef.compilationError(fileBased = true)
        else "Temporarily disabled in Scala 3"
      }
      "exception" - Predef.exception(fileBased = true)
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

    test("dependencies") {
      test("auto dependency") {
        test("example") {
          if (TestUtil.isScala212) {
            val code =
              """import $ivy.`org.scalacheck::scalacheck:1.14.0`
                |import org.scalacheck.ScalacheckShapeless._
                |""".stripMargin
            val res = interpreter.execute(code)
            assert(res.success)
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
          assert(res.success)
        }
      }
    }

    def variableInspectorTest(): Unit = {

      implicit class ExecuteResultOps(private val res: ExecuteResult) {
        def assertSuccess(): ExecuteResult = {
          assert(res.success)
          res
        }
      }

      implicit class DisplayDataOps(private val data: DisplayData) {
        def text: String =
          data.data.getOrElse("text/plain", "")
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
    }
    test("variable inspector") {
      if (TestUtil.isScala2) variableInspectorTest()
      else "disabled"
    }
  }

}
