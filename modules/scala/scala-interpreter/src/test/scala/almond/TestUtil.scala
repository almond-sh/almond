package almond

import java.util.UUID

import almond.channels.Channel
import almond.interpreter.{ExecuteResult, Message}
import almond.protocol.{Execute => ProtocolExecute, _}
import almond.kernel.{ClientStreams, Kernel, KernelThreads}
import almond.TestLogging.logCtx
import ammonite.util.Colors
import cats.effect.IO
import fs2.Stream
import utest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

object TestUtil {

  private def noCrLf(input: String): String =
    input.replace("\r\n", "\n")

  def noCrLf(res: ExecuteResult): ExecuteResult =
    res match {
      case s: ExecuteResult.Success =>
        ExecuteResult.Success(
          s.data.copy(data = s.data.data.map {
            case ("text/plain", v) => ("text/plain", noCrLf(v))
            case (k, v) => (k, v)
          })
        )
      case other => other
    }

  def isScala212 =
    scala.util.Properties.versionNumberString.startsWith("2.12.")
  def isScala2 = almond.api.Properties.actualScalaVersion.startsWith("2.")

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

    def run(inputs: Seq[(String, String)], publish: Seq[String] = Nil): Unit = {

      val (input, replies) = inputs.unzip

      val sessionId = UUID.randomUUID().toString
      val lastMsgId = UUID.randomUUID().toString

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId))

      assert(input.nonEmpty)

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

      val replies0 = streams.executeReplies.filter(_._2.nonEmpty)
      val expectedReplies = replies
        .zipWithIndex
        .collect {
          case (r, idx) if r.nonEmpty =>
            (idx + 1) -> r
        }
        .toMap

      val publish0 = streams.displayDataText

      for ((a, b) <- publish0.zip(publish) if a != b)
        System.err.println(s"Expected $b, got $a")
      for (k <- replies0.keySet.intersect(expectedReplies.keySet) if replies0.get(k) != expectedReplies.get(k))
        System.err.println(s"At line $k: expected ${expectedReplies(k)}, got ${replies0(k)}")
      for (k <- replies0.keySet.--(expectedReplies.keySet))
        System.err.println(s"At line $k: expected nothing, got ${replies0(k)}")
      for (k <- expectedReplies.keySet.--(replies0.keySet))
        System.err.println(s"At line $k: expected ${expectedReplies(k)}, got nothing")

      assert(replies0.mapValues(noCrLf).toMap == expectedReplies.mapValues(noCrLf).toMap)
      assert(publish0.map(noCrLf) == publish.map(noCrLf))
    }
  }

}
