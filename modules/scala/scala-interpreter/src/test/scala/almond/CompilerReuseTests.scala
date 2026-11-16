package almond

import almond.interpreter.api.{DisplayData, ExecuteResult}
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import utest._

object CompilerReuseTests extends TestSuite {

  private def newInterpreter(): ScalaInterpreter =
    new ScalaInterpreter(params = interpreterParams, logCtx = logCtx)

  val tests = Tests {
    test("cells without compiler options") {
      val interpreter = newInterpreter()
      try {
        assert(interpreter.execute("val x = 1").success)
        val manager  = interpreter.ammInterp.compilerManager
        val compiler = manager.compiler

        val result = interpreter.execute("val y = x + 1")
        assert(result == ExecuteResult.Success(DisplayData.text("y: Int = 2")))
        assert(manager.compiler eq compiler)

        assert(interpreter.execute("case class Point(x: Int, y: Int)").success)
        assert(interpreter.execute("assert(Point(x, y).y == 2)").success)
        assert(manager.compiler eq compiler)
      }
      finally interpreter.shutdown()
    }

    test("cells with compiler options") {
      val interpreter = newInterpreter()
      try {
        assert(interpreter.execute("val x = 1").success)
        val manager  = interpreter.ammInterp.compilerManager
        val compiler = manager.compiler

        assert(interpreter.execute("//> using option -nowarn\nval y = x + 1").success)
        val configuredCompiler = manager.compiler
        assert(configuredCompiler ne compiler)

        val result = interpreter.execute("val z = y + 1")
        assert(result == ExecuteResult.Success(DisplayData.text("z: Int = 3")))
        assert(manager.compiler eq configuredCompiler)
      }
      finally interpreter.shutdown()
    }
  }
}
