package almond

import almond.interpreter.api.{DisplayData, ExecuteResult}
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import utest._

object LineNumbersTests extends TestSuite {

  val tests = Tests {
    // The code of vars and lazy vals is rewritten when they're auto-updated (Scala 2), which
    // shouldn't change the line numbers of the code that follows (that debuggers rely on)
    test("vars and lazy vals don't shift lines") {
      val i = new ScalaInterpreter(
        params = interpreterParams.copy(autoUpdateVars = true, autoUpdateLazyVals = true),
        logCtx = logCtx
      )
      val result = i.execute(
        """var n = 1
          |lazy val m =
          |  n + 1
          |throw new Exception("THE ERROR")
          |""".stripMargin
      )
      result match {
        case e: ExecuteResult.Error =>
          val frame = e.stackTrace.find(_.contains("$Helper.<init>"))
          assert(frame.exists(_.contains(".sc:4)")))
        case other =>
          throw new Exception(s"Unexpected result: $other")
      }
    }
    test("vars and lazy vals still auto-updated") {
      val i = new ScalaInterpreter(
        params = interpreterParams.copy(autoUpdateVars = true, autoUpdateLazyVals = true),
        logCtx = logCtx
      )
      val first = i.execute(
        """var n = 1
          |lazy val m =
          |  n + 1
          |""".stripMargin
      )
      assert(first.success)
      val next = noCrLf(i.execute("n += 1; val l = m"))
      assert(next == ExecuteResult.Success(DisplayData.text("l: Int = 3")))
    }
  }
}
