package almond.interpreter

import java.util.concurrent.Executors

import almond.interpreter.TestInterpreter.StringBOps
import almond.logger.LoggerContext
import almond.protocol.RawJson
import almond.util.ThreadUtil
import cats.effect.unsafe.IORuntime
import cats.implicits._
import utest._

import scala.concurrent.ExecutionContext

object IOInterpreterTests extends TestSuite {

  private val pool = Executors.newScheduledThreadPool(4, ThreadUtil.daemonThreadFactory("test"))
  private val ec   = ExecutionContext.fromExecutorService(pool)

  override def utestAfterAll() =
    pool.shutdown()

  val tests = Tests {

    test("completion check") {

      test("cancel previous requests") {

        val interpreter: Interpreter = new TestInterpreter
        val ioInterpreter: IOInterpreter =
          new InterpreterToIOInterpreter(interpreter, ec, LoggerContext.nop)

        val ios = Seq(
          // the "cancel" completion checks are only completed if they are cancelled
          ioInterpreter.isComplete("cancel"),
          ioInterpreter.isComplete("cancel"),
          ioInterpreter.isComplete("other")
        )

        val t = ios.toList.sequence

        val res = t.unsafeRunSync()(IORuntime.global)
        val expectedRes = Seq(
          Some(IsCompleteResult.Invalid),
          Some(IsCompleteResult.Invalid),
          Some(IsCompleteResult.Complete)
        )

        assert(res == expectedRes)
      }

    }

    test("completion") {

      test("cancel previous requests") {

        val interpreter: Interpreter = new TestInterpreter
        val ioInterpreter: IOInterpreter =
          new InterpreterToIOInterpreter(interpreter, ec, LoggerContext.nop)

        val ios = Seq(
          // the "cancel" completions are only completed if they are cancelled
          ioInterpreter.complete("cancel"),
          ioInterpreter.complete("cancel"),
          ioInterpreter.complete("other")
        )

        val t = ios.toList.sequence

        val res = t.unsafeRunSync()(IORuntime.global)
        val expectedRes = Seq(
          Completion(0, "cancel".length, Seq("cancelled")),
          Completion(0, "cancel".length, Seq("cancelled")),
          Completion("other".length, "other".length, Seq("?"))
        )

        assert(res == expectedRes)
      }

    }

    test("inspection") {

      test("cancel previous requests") {

        val interpreter: Interpreter = new TestInterpreter
        val ioInterpreter: IOInterpreter =
          new InterpreterToIOInterpreter(interpreter, ec, LoggerContext.nop)

        val ios = Seq(
          // the "cancel" inspections are only completed if they are cancelled
          ioInterpreter.inspect("cancel", 0, 0),
          ioInterpreter.inspect("cancel", 0, 0),
          ioInterpreter.inspect("other", 0, 0)
        )

        val t = ios.toList.sequence

        val res = t.unsafeRunSync()(IORuntime.global)
        val expectedRes = Seq(
          Some(Inspection(Map("cancelled" -> RawJson("true".bytes)))),
          Some(Inspection(Map("cancelled" -> RawJson("true".bytes)))),
          Some(Inspection(Map("result" -> RawJson("\"other: code\"".bytes))))
        )

        assert(res == expectedRes)
      }

    }

  }

}
