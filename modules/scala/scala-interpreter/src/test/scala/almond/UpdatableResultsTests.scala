package almond

import almond.internals.UpdatableResults
import almond.interpreter.api.DisplayData
import almond.logger.LoggerContext
import utest._

import scala.concurrent.ExecutionContext

object UpdatableResultsTests extends TestSuite {

  private val ec: ExecutionContext =
    new ExecutionContext {
      def execute(runnable: Runnable) = runnable.run()
      def reportFailure(cause: Throwable) = ()
    }

  val tests = Tests {

    "early update" - {
      val updates = new java.util.concurrent.ConcurrentLinkedQueue[DisplayData]
      val r = new UpdatableResults(ec, LoggerContext.nop, updates.add)
      r.update("<foo>", "value", last = true)
      val data = r.add(DisplayData.text("Foo <foo>"), Map("<foo>" -> "---"))
      val expectedData = DisplayData.text("Foo value")
      assert(data == expectedData)
      assert(r.earlyUpdates.isEmpty)
    }

  }

}
