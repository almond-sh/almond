package almond

import almond.interpreter.api.{DisplayData, ExecuteResult}
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import utest._

/** Checks that the compiler instance is re-used across cells, and only re-created when compiler
  * options change
  */
object CompilerReuseTests extends TestSuite {

  private def withInterpreter[T](f: ScalaInterpreter => T): T = {
    val interpreter = new ScalaInterpreter(params = interpreterParams, logCtx = logCtx)
    try f(interpreter)
    finally interpreter.shutdown()
  }

  val tests = Tests {
    test("cells without compiler options") {
      withInterpreter { interpreter =>
        assert(interpreter.execute("val x = 1").success)
        val manager  = interpreter.ammInterp.compilerManager
        val compiler = manager.compiler

        val result = interpreter.execute("val y = x + 1")
        assert(result == ExecuteResult.Success(DisplayData.text("y: Int = 2")))
        assert(manager.compiler eq compiler)

        assert(interpreter.execute("val z = y + 1").success)
        assert(manager.compiler eq compiler)
      }
    }

    test("cells with compiler options") {
      withInterpreter { interpreter =>
        assert(
          interpreter.execute(
            """@deprecated("foo", "0.1")
              |def getValue(): Int = 2
              |""".stripMargin
          ).success
        )
        // only a warning for now
        assert(interpreter.execute("val x = getValue()").success)
        val manager  = interpreter.ammInterp.compilerManager
        val compiler = manager.compiler

        // the options make the deprecation warning fatal, which ensures they're actually used
        val withOptions = interpreter.execute(
          """//> using option "-Xfatal-warnings" "-deprecation"
            |val y = getValue()
            |""".stripMargin
        )
        assert(!withOptions.success)
        val configuredCompiler = manager.compiler
        assert(configuredCompiler ne compiler)

        // cells without directives re-use the configured compiler, so its options still apply
        assert(!interpreter.execute("val z = getValue()").success)
        assert(manager.compiler eq configuredCompiler)

        val result = interpreter.execute("val n = x + 1")
        assert(result == ExecuteResult.Success(DisplayData.text("n: Int = 3")))
        assert(manager.compiler eq configuredCompiler)
      }
    }
  }
}
