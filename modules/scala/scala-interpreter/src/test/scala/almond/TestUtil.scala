package almond

import java.util.UUID

import almond.channels.Channel
import almond.interpreter.Message
import almond.protocol.{Execute => ProtocolExecute, _}
import almond.kernel.{ClientStreams, Kernel, KernelThreads}
import almond.TestLogging.logCtx
import ammonite.util.Colors
import argonaut.Json
import cats.effect.IO
import fs2.Stream
import utest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

object TestUtil {

  def isScala211 =
    scala.util.Properties.versionNumberString.startsWith("2.11.")
  def isScala212 =
    scala.util.Properties.versionNumberString.startsWith("2.12.")

  implicit class IOOps[T](private val io: IO[T]) extends AnyVal {
    // beware this is not *exactly* a timeout, more a max idle time say… (see the scaladoc of IO.unsafeRunTimed)
    def unsafeRunTimedOrThrow(duration: Duration = Duration.Inf): T =
      io.unsafeRunTimed(duration).getOrElse {
        throw new Exception("Timeout")
      }
  }

  def execute(
    sessionId: String,
    code: String,
    msgId: String = UUID.randomUUID().toString,
    stopOnError: Boolean = true
  ) =
    Message(
      Header(
        msgId,
        "test",
        sessionId,
        ProtocolExecute.requestType.messageType,
        Some(Protocol.versionStr)
      ),
      ProtocolExecute.Request(code, stop_on_error = Some(stopOnError))
    ).on(Channel.Requests)


  class SessionRunner(
    interpreterEc: ExecutionContext,
    bgVarEc: ExecutionContext,
    threads: KernelThreads
  ) {

    def run(session: (String, String)*): Unit = {

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      val stopWhen: (Channel, Message[Json]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      assert(session.nonEmpty)

      val input = session.map(_._1)

      val input0 = Stream(
        input.init.map(s => execute(sessionId, s)) :+
          execute(sessionId, input.last, lastMsgId): _*
      )

      val streams = ClientStreams.create(input0, stopWhen)

      val interpreter = new ScalaInterpreter(
        params = ScalaInterpreterParams(
          updateBackgroundVariablesEcOpt = Some(bgVarEc),
          initialColors = Colors.BlackWhite
        ),
        logCtx = logCtx
      )

      val t = Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .flatMap(_.run(streams.source, streams.sink))

      t.unsafeRunTimedOrThrow()

      val output = streams.output.flatMap(_.split('\n')).filter(_.nonEmpty)
      val expectedOutput = session.flatMap(_._2.split('\n')).filter(_.nonEmpty)

      for (((a, b), idx) <- output.zip(expectedOutput).zipWithIndex if a != b)
        System.err.println(s"At line $idx: expected $b, got $a")
      for (elem <- output.drop(expectedOutput.length))
        System.err.println(s"Got extra element: $elem")
      for (elem <- expectedOutput.drop(output.length))
        System.err.println(s"Expected extra element: $elem")
      assert(output == expectedOutput)
    }
  }

}
