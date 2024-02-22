package almond

import java.util.UUID

import almond.channels.Channel
import almond.interpreter.messagehandlers.MessageHandler
import almond.interpreter.{ExecuteResult, Message}
import almond.protocol.{Execute => ProtocolExecute, _}
import almond.kernel.{Kernel, KernelThreads}
import almond.testkit.{ClientStreams, Dsl}
import almond.testkit.TestLogging.logCtx
import almond.util.SequentialExecutionContext
import ammonite.util.Colors
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.eed3si9n.expecty.Expecty.expect
import fs2.Stream

import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import scala.collection.compat._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

object TestUtil {

  private def noCrLf(input: String): String =
    input.replace("\r\n", "\n")

  def noCrLf(res: ExecuteResult): ExecuteResult =
    res match {
      case s: ExecuteResult.Success =>
        ExecuteResult.Success(
          s.data.copy(data = s.data.data.map {
            case ("text/plain", v) => ("text/plain", noCrLf(v))
            case (k, v)            => (k, v)
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
      duration match {
        case finite: FiniteDuration =>
          io.unsafeRunTimed(finite)(IORuntime.global).getOrElse {
            throw new Exception("Timeout")
          }
        case _ =>
          io.unsafeRunSync()(IORuntime.global)
      }
  }

  final class KernelSession(kernel: Kernel) extends Dsl.Session {
    def run(streams: ClientStreams): Unit =
      kernel.run(streams.source, streams.sink, Nil)
        .unsafeRunTimedOrThrow()
  }

  final case class KernelRunner(kernel: Seq[String] => Kernel) extends Dsl.Runner {
    private def apply(options: String*): KernelSession =
      new KernelSession(kernel(options))
    private def withExtraClassPath(extraClassPath: String*)(options: String*): KernelSession =
      if (extraClassPath.isEmpty) apply(options: _*)
      else sys.error("Extra startup JARs unsupported in unit tests")
    private def withLauncherOptions(launcherOptions: String*)(options: String*): KernelSession = {
      if (launcherOptions.nonEmpty)
        System.err.println(
          s"Warning: ignoring extra launcher options ${launcherOptions.mkString(" ")} in unit test"
        )
      apply(options: _*)
    }

    def output = None

    def withSession[T](options: String*)(f: Dsl.Session => T)(implicit
      sessionId: Dsl.SessionId
    ): T = {
      val sess = apply(options: _*)
      f(sess)
    }
    def withExtraClassPathSession[T](extraClassPath: String*)(options: String*)(
      f: Dsl.Session => T
    )(implicit sessionId: Dsl.SessionId): T = {
      val sess = withExtraClassPath(extraClassPath: _*)(options: _*)
      f(sess)
    }
    def withLauncherOptionsSession[T](launcherOptions: String*)(options: String*)(
      f: Dsl.Session => T
    )(implicit sessionId: Dsl.SessionId): T = {
      val sess = withLauncherOptions(launcherOptions: _*)(options: _*)
      f(sess)
    }
  }

  private case class Options(
    predef: List[String] = Nil,
    toreeMagics: Boolean = false,
    toreeApi: Boolean = false
  )

  private val optionsParser: caseapp.Parser[Options] = caseapp.Parser.derive

  def kernelRunner[T](
    threads: KernelThreads,
    interpreterEc: ExecutionContext,
    processParams: ScalaInterpreterParams => ScalaInterpreterParams = identity
  ): KernelRunner = KernelRunner {

    options =>

      val opt = optionsParser.parse(options) match {
        case Left(e) => throw new Exception(e.toString)
        case Right((opt, extra)) =>
          assert(extra.isEmpty, "Unexpected trailing values in kernel arguments")
          opt
      }

      val interpreter = new ScalaInterpreter(
        params = processParams {
          ScalaInterpreterParams(
            initialColors = Colors.BlackWhite,
            updateBackgroundVariablesEcOpt = Some(new SequentialExecutionContext),
            predefFiles = opt.predef.map(Paths.get(_)),
            toreeMagics = opt.toreeMagics,
            toreeApiCompatibility = opt.toreeApi
          )
        },
        logCtx = logCtx
      )

      Kernel.create(interpreter, interpreterEc, threads, logCtx)
        .unsafeRunTimedOrThrow()
  }

  implicit class KernelOps(private val kernel: Kernel) extends AnyVal {
    def execute(
      code: String,
      reply: String = null,
      expectError: Boolean = false,
      expectInterrupt: Boolean = false,
      errors: Seq[(String, String, List[String])] = null,
      displaysText: Seq[String] = null,
      displaysHtml: Seq[String] = null,
      displaysTextUpdates: Seq[String] = null,
      displaysHtmlUpdates: Seq[String] = null,
      replyPayloads: Seq[String] = null,
      ignoreStreams: Boolean = false,
      stdout: String = null,
      stderr: String = null,
      waitForUpdateDisplay: Boolean = false,
      handler: MessageHandler = MessageHandler.discard { case _ => }
    )(implicit sessionId: Dsl.SessionId): Unit = {

      val expectError0   = expectError || Option(errors).nonEmpty
      val ignoreStreams0 = ignoreStreams || Option(stdout).nonEmpty || Option(stderr).nonEmpty

      val input = Stream(
        TestUtil.execute(code, stopOnError = !expectError0)
      )

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        if (waitForUpdateDisplay)
          (_, m) => IO.pure(m.header.msg_type == "update_display_data")
        else
          (_, m) => IO.pure(m.header.msg_type == "execute_reply")

      val streams = ClientStreams.create(input, stopWhen, handler)

      kernel.run(streams.source, streams.sink, Nil)
        .unsafeRunTimedOrThrow()

      val requestsMessageTypes = streams.generatedMessageTypes(Set(Channel.Requests)).toVector
      val publishMessageTypes = streams.generatedMessageTypes(Set(Channel.Publish)).toVector
        .filter(if (ignoreStreams0) _ != "stream" else _ => true)

      val expectedRequestsMessageTypes =
        if (reply == null && !expectError0)
          Nil
        else
          Seq("execute_reply")
      expect(requestsMessageTypes == Seq("execute_reply"))

      if (expectInterrupt) {
        val controlMessageTypes = streams.generatedMessageTypes(Set(Channel.Control)).toVector
        val expectedControlMessageTypes = Seq("interrupt_reply")
        expect(controlMessageTypes == expectedControlMessageTypes)
      }

      val expectedPublishMessageTypes = {
        val displayDataCount = Seq(
          Option(displaysText).fold(0)(_.length),
          Option(displaysHtml).fold(0)(_.length)
        ).max
        val updateDisplayDataCount = Seq(
          Option(displaysTextUpdates).fold(0)(_.length),
          Option(displaysHtmlUpdates).fold(0)(_.length)
        ).max
        val prefix = Seq("execute_input") ++
          Seq.fill(displayDataCount)("display_data") ++
          Seq.fill(updateDisplayDataCount)("update_display_data")
        if (expectError0)
          prefix :+ "error"
        else if (reply == null || reply.isEmpty)
          prefix
        else
          prefix :+ "execute_result"
      }
      expect(publishMessageTypes == expectedPublishMessageTypes)

      if (stdout != null) {
        val stdoutMessages = streams.output.mkString
        expect(stdout == stdoutMessages)
      }

      if (stderr != null) {
        val stderrMessages = streams.errorOutput.mkString
        expect(stderr == stderrMessages)
      }

      val replies = streams.executeReplies.toVector.sortBy(_._1).map(_._2)
      expect(replies == Option(reply).toVector)

      if (replyPayloads != null) {
        val gotReplyPayloads = streams.executeReplyPayloads
          .toVector
          .sortBy(_._1)
          .flatMap(_._2)
          .map(_.value)
          .map(new String(_, StandardCharsets.UTF_8))
        expect(replyPayloads == gotReplyPayloads)
      }

      for (expectedTextDisplay <- Option(displaysText)) {
        import ClientStreams.RawJsonOps

        val textDisplay = streams.displayData.collect {
          case (data, false) =>
            data.data.get("text/plain")
              .map(_.stringOrEmpty)
              .getOrElse("")
        }

        expect(textDisplay == expectedTextDisplay)
      }

      val receivedErrors = streams.executeErrors.toVector.sortBy(_._1).map(_._2)
      expect(errors == null || receivedErrors == errors)

      for (expectedHtmlDisplay <- Option(displaysHtml)) {
        import ClientStreams.RawJsonOps

        val htmlDisplay = streams.displayData.collect {
          case (data, false) =>
            data.data.get("text/html")
              .map(_.stringOrEmpty)
              .getOrElse("")
        }

        expect(htmlDisplay == expectedHtmlDisplay)
      }

      for (expectedTextDisplayUpdates <- Option(displaysTextUpdates)) {
        import ClientStreams.RawJsonOps

        val textDisplayUpdates = streams.displayData.collect {
          case (data, true) =>
            data.data.get("text/plain")
              .map(_.stringOrEmpty)
              .getOrElse("")
        }

        expect(textDisplayUpdates == expectedTextDisplayUpdates)
      }

      for (expectedHtmlDisplayUpdates <- Option(displaysHtmlUpdates)) {
        import ClientStreams.RawJsonOps

        val htmlDisplayUpdates = streams.displayData.collect {
          case (data, true) =>
            data.data.get("text/html")
              .map(_.stringOrEmpty)
              .getOrElse("")
        }

        expect(htmlDisplayUpdates == expectedHtmlDisplayUpdates)
      }
    }
  }

  def execute(
    code: String,
    msgId: String = UUID.randomUUID().toString,
    stopOnError: Boolean = true
  )(implicit sessionId: Dsl.SessionId) =
    Message(
      Header(
        msgId,
        "test",
        sessionId.sessionId,
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

      implicit val sessionId: Dsl.SessionId = Dsl.SessionId()
      val lastMsgId                         = UUID.randomUUID().toString

      val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
        (_, m) =>
          IO.pure(
            m.header.msg_type == "execute_reply" && m.parent_header.exists(_.msg_id == lastMsgId)
          )

      expect(input.nonEmpty)

      val input0 = Stream(
        input.init.map(s => execute(s)) :+
          execute(input.last, lastMsgId): _*
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
        .flatMap(_.run(streams.source, streams.sink, Nil))

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
      for (
        k <- replies0.keySet.intersect(expectedReplies.keySet).toVector.sorted
        if replies0.get(k) != expectedReplies.get(k)
      )
        System.err.println(s"At line $k: expected ${expectedReplies(k)}, got ${replies0(k)}")
      for (k <- replies0.keySet.--(expectedReplies.keySet))
        System.err.println(s"At line $k: expected nothing, got ${replies0(k)}")
      for (k <- expectedReplies.keySet.--(replies0.keySet))
        System.err.println(s"At line $k: expected ${expectedReplies(k)}, got nothing")

      expect(replies0.view.mapValues(noCrLf).toMap == expectedReplies.view.mapValues(noCrLf).toMap)
      expect(publish0.map(noCrLf) == publish.map(noCrLf))
    }
  }

  def comparePublishMessageTypes(expected: Seq[Set[String]], got: Seq[String]): Boolean =
    expected.map(_.size).sum == got.length && {
      val it = got.iterator
        // Workaround for https://github.com/scala/bug/issues/12803
        .map(identity)
      expected.forall { expectedGroup =>
        val got0 = it.take(expectedGroup.size).toSet
        expectedGroup == got0
      }
    }

}
