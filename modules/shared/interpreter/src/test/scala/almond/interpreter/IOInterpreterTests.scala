package almond.interpreter

import java.util.concurrent.Executors

import almond.logger.LoggerContext
import almond.util.ThreadUtil
import cats.implicits._
import utest._

import scala.concurrent.ExecutionContext

object IOInterpreterTests extends TestSuite {

  private val pool = Executors.newScheduledThreadPool(4, ThreadUtil.daemonThreadFactory("test"))
  private val ec = ExecutionContext.fromExecutorService(pool)

  override def utestAfterAll() = {
    pool.shutdown()
  }

  val tests = Tests {

    "completion" - {

      "cancel previous requests" - {

        val interpreter: Interpreter = new TestInterpreter
        val ioInterpreter: IOInterpreter = new InterpreterToIOInterpreter(interpreter, ec, LoggerContext.nop)

        val ios = Seq(
          // the "cancel" completions are only completed if they are cancelled
          ioInterpreter.complete("cancel"),
          ioInterpreter.complete("cancel"),
          ioInterpreter.complete("other")
        )

        val t = ios.toList.sequence

        val res = t.unsafeRunSync()
        val expectedRes = Seq(
          Completion(0, "cancel".length, Seq("cancelled")),
          Completion(0, "cancel".length, Seq("cancelled")),
          Completion("other".length, "other".length, Seq("?"))
        )

        assert(res == expectedRes)
      }

    }

  }

}
