package almond.lsp

import utest._

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

object LspTasksTests extends TestSuite {

  private final class Fixture(delay: FiniteDuration) {
    val tasks   = new LspTasks(delay = delay)
    val changes = new LinkedBlockingQueue[Seq[LspTask]]
    tasks.subscribe(changes.put(_))
    def next(): Seq[LspTask] = {
      val res = changes.poll(5L, TimeUnit.SECONDS)
      assert(res != null)
      res
    }
  }

  private def withFixture[T](f: Fixture => T): T =
    withFixtureDelay(200.millis)(f)

  private def withFixtureDelay[T](delay: FiniteDuration)(f: Fixture => T): T = {
    val fixture = new Fixture(delay)
    try f(fixture)
    finally fixture.tasks.close()
  }

  val tests = Tests {

    test("initial") {
      withFixture { fx =>
        assert(fx.next() == Nil)
      }
    }

    test("short tasks are not reported") {
      withFixture { fx =>
        fx.next()
        fx.tasks(LspTask("a", "A"))(())
        Thread.sleep(400L)
        assert(fx.changes.isEmpty)
      }
    }

    test("long tasks are reported") {
      withFixture { fx =>
        fx.next()
        val a = LspTask("a", "A", Some("first step"))
        fx.tasks.start(a)
        assert(fx.changes.isEmpty)
        assert(fx.next() == Seq(a))
        val a2 = a.copy(message = Some("second step"))
        fx.tasks.start(a2)
        assert(fx.next() == Seq(a2))
        fx.tasks.done("a")
        assert(fx.next() == Nil)
      }
    }

    test("restarted tasks wait again") {
      val delay = 1.second
      withFixtureDelay(delay) { fx =>
        fx.next()
        fx.tasks.start(LspTask("a", "A"))
        fx.tasks.done("a")
        Thread.sleep(300L)
        // the reveal scheduled for the first start mustn't reveal this one early
        fx.tasks.start(LspTask("a", "A"))
        val restart = System.nanoTime()
        Thread.sleep(900L)
        // sleeps can overshoot a lot on slow CI runners: only check that nothing
        // was reported if the second reveal isn't due yet
        if ((System.nanoTime() - restart).nanos < delay)
          assert(fx.changes.isEmpty)
        assert(fx.next() == Seq(LspTask("a", "A")))
      }
    }
  }
}
