package almond

import java.nio.file.{Path, Paths}

import almond.interpreter.api.DisplayData
import almond.interpreter.{Completion, ExecuteResult, Interpreter}
import almond.TestLogging.logCtx
import almond.TestUtil._
import almond.amm.AmmInterpreter
import ammonite.util.Colors
import coursierapi.{Dependency, Module}
import utest._

object ScalaInterpreterTests extends TestSuite {

  private val sbv = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  private val interpreter: Interpreter =
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

      val res = interp.execute("val m = 2 * n")
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

      val res = interp.execute("val m = 2 * n")
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
        } catch {
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
        } catch {
          case e: AmmInterpreter.PredefException =>
            val msgOpt = Option(e.getCause).flatMap(e0 => Option(e0.getMessage))
            assert(msgOpt.contains("foo"))
            true
        }

      assert(res)
    }
  }

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
        val code = "repl.la"
        val expectedRes = Completion(5, 7, Seq("lastException"))
        val res = interpreter.complete(code, code.length)
        assert(res == expectedRes)
      }

      * - {
        val code = "Lis"
        val expectedRes = Completion(0, 3, Seq("List"))
        val alternativeExpectedRes = Completion(0, 3, Seq("scala.List"))
        val res0 = interpreter.complete(code, code.length)
        val res = res0.copy(
          completions = res0.completions.filter(expectedRes.completions.toSet)
        )
        val alternativeRes = res0.copy(
          completions = res0.completions.filter(alternativeExpectedRes.completions.toSet)
        )
        assert(res == expectedRes || alternativeRes == alternativeExpectedRes)
      }

      * - {
        val code = "HashM"

        val extraCompletions =
          if (isScala211 || isScala212)
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
        val res0 = interpreter.complete(code, code.length)
        val res = res0.copy(
          completions = res0.completions.filter(expectedRes.completions.toSet)
        )
        assert(res == expectedRes)
      }

    }

    "predef code" - {
      "simple" - Predef.simple()
      "no variable name" - Predef.noVariableName()
      "compilation error" - Predef.compilationError()
      "exception" - Predef.exception()
    }

    "predef files" - {
      "simple" - Predef.simple(fileBased = true)
      "no variable name" - Predef.noVariableName(fileBased = true)
      "compilation error" - Predef.compilationError(fileBased = true)
      "exception" - Predef.exception(fileBased = true)
    }

    "silent" - {
      "defaults false" - {
        val code = "val silent = kernel.silent"
        val res = interpreter.execute(code)
        val expectedRes = ExecuteResult.Success(DisplayData.text("silent: Boolean = false"))
        assert(res == expectedRes)
      }
      "can be set to true" - {
        val code =
          """
            | val silentBefore = kernel.silent
            | kernel.silent(true)
            | val silentAfter = kernel.silent
            |""".stripMargin
        val res = interpreter.execute(code)
        val expectedRes = ExecuteResult.Success(DisplayData.text(
          """silentBefore: Boolean = false
            |silentAfter: Boolean = true""".stripMargin))
        assert(res == expectedRes)
      }
//      "can be set to false" - {
//        val code =
//          """
//            | kernel.silent(true)
//            | val silentBefore = kernel.silent
//            | kernel.silent(false)
//            | val silentAfter = kernel.silent
//            |""".stripMargin
//        val res = interpreter.execute(code)
//        val expectedRes = ExecuteResult.Success(DisplayData.text(
//          """silentBefore: Boolean = true
//            |silentAfter: Boolean = false""".stripMargin))
//        assert(res == expectedRes)
//      }
    }

    "dependencies" - {
      "auto dependency" - {
        "example" - {
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

      "auto version" - {
        "simple" - {
          val code =
            """import $ivy.`org.scalacheck::scalacheck:_`
              |import org.scalacheck.Arbitrary
              |""".stripMargin
          val res = interpreter.execute(code)
          assert(res.success)
        }
      }
    }

  }

}
