package almond

import almond.internals.UpdatableResults
import almond.interpreter.api.DisplayData
import almond.logger.LoggerContext
import almond.util.SequentialExecutionContext
import utest._

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

object UpdatableResultsTests extends TestSuite {

  private val ec: ExecutionContext = new SequentialExecutionContext

  val tests = Tests {

    test("early update") {
      val updates = new java.util.concurrent.ConcurrentLinkedQueue[DisplayData]
      val r       = new UpdatableResults(ec, LoggerContext.nop, updates.add)
      r.update("<foo>", "value", last = true)
      val data         = r.add(DisplayData.text("Foo <foo>"), Map("<foo>" -> "---"))
      val expectedData = DisplayData.text("Foo value")
      assert(data == expectedData)
      assert(r.earlyUpdates.isEmpty)
    }

    def texts(updates: java.util.concurrent.ConcurrentLinkedQueue[DisplayData]): Seq[String] =
      updates.asScala.toVector.map { d =>
        d.detailedData.get("text/plain") match {
          case Some(DisplayData.Value.String(s)) => s
          case _                                 => ""
        }
      }

    test("coalesce updates") {
      val updates = new java.util.concurrent.ConcurrentLinkedQueue[DisplayData]
      // long interval, so that only the first update and explicit flushes send anything here
      val r = new UpdatableResults(ec, LoggerContext.nop, updates.add, Some(1.hour))
      r.add(DisplayData.text("n = <n>"), Map("<n>" -> "0"))

      val computed = new AtomicInteger
      def value(s: String): String = {
        computed.incrementAndGet()
        s
      }

      // first update: sent right away
      r.update("<n>", value("1"), last = false)
      assert(texts(updates) == Seq("n = 1"))

      // next ones: coalesced, and not computed until they're sent
      for (i <- 2 to 1000)
        r.update("<n>", value(i.toString), last = false)
      assert(texts(updates) == Seq("n = 1"))
      assert(computed.get() == 1)

      r.flush()
      assert(texts(updates) == Seq("n = 1", "n = 1000"))
      assert(computed.get() == 2)

      // nothing left to send
      r.flush()
      assert(texts(updates) == Seq("n = 1", "n = 1000"))
    }

    test("send pending updates after interval") {
      val updates = new java.util.concurrent.ConcurrentLinkedQueue[DisplayData]
      val r       = new UpdatableResults(ec, LoggerContext.nop, updates.add, Some(50.millis))
      r.add(DisplayData.text("n = <n>"), Map("<n>" -> "0"))

      r.update("<n>", "1", last = false)
      r.update("<n>", "2", last = false)
      r.update("<n>", "3", last = false)
      assert(texts(updates) == Seq("n = 1"))

      val deadline = System.currentTimeMillis() + 10000L
      while (updates.size() < 2 && System.currentTimeMillis() < deadline)
        Thread.sleep(10L)
      assert(texts(updates) == Seq("n = 1", "n = 3"))
    }

    test("no coalescing without min update interval") {
      val updates = new java.util.concurrent.ConcurrentLinkedQueue[DisplayData]
      val r       = new UpdatableResults(ec, LoggerContext.nop, updates.add, None)
      r.add(DisplayData.text("n = <n>"), Map("<n>" -> "0"))

      val computed = new AtomicInteger
      def value(s: String): String = {
        computed.incrementAndGet()
        s
      }

      for (i <- 1 to 5)
        r.update("<n>", value(i.toString), last = false)
      // each update computed and sent right away
      assert(computed.get() == 5)
      assert(texts(updates) == (1 to 5).map(i => s"n = $i"))

      // nothing pending
      r.flush()
      assert(texts(updates) == (1 to 5).map(i => s"n = $i"))
    }

    test("pending update before add") {
      val updates = new java.util.concurrent.ConcurrentLinkedQueue[DisplayData]
      val r       = new UpdatableResults(ec, LoggerContext.nop, updates.add, Some(1.hour))
      // Sent right away (to earlyUpdates, as <foo> isn't known yet)
      r.update("<foo>", "first", last = false)
      // Pending
      r.update("<foo>", "second", last = false)
      val data = r.add(DisplayData.text("Foo <foo>"), Map("<foo>" -> "---"))
      assert(data == DisplayData.text("Foo second"))
      assert(r.earlyUpdates.isEmpty)
      assert(updates.isEmpty)
    }

  }

}
