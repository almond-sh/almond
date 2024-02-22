package almond.testkit

import almond.channels.Channel
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.protocol.{Execute => ProtocolExecute, _}
import cats.effect.IO
import com.eed3si9n.expecty.Expecty.expect
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import fs2.Stream
import io.github.alexarchambault.testutil.TestOutput

import java.nio.charset.StandardCharsets
import java.util.UUID

import scala.util.Properties

object Dsl {

  trait Runner {
    def withSession[T](options: String*)(f: Session => T)(implicit sessionId: SessionId): T
    def withExtraClassPathSession[T](extraClassPath: String*)(options: String*)(f: Session => T)(
      implicit sessionId: SessionId
    ): T
    def withLauncherOptionsSession[T](launcherOptions: String*)(options: String*)(f: Session => T)(
      implicit sessionId: SessionId
    ): T

    def differedStartUp: Boolean = false
    def output: Option[TestOutput]
  }

  trait Session {
    def run(streams: ClientStreams): Unit
  }

  private implicit class CustomStringOps(private val str: String) extends AnyVal {
    def trimLines: String =
      str.linesIterator
        .zip(str.linesWithSeparators)
        .map {
          case (l, lSep) =>
            val trimmed = l.trim
            if (l == trimmed) lSep
            else trimmed + lSep.drop(l.length)
        }
        .mkString
  }

  private def stopWhen(replyType: String): (Channel, Message[RawJson]) => IO[Boolean] = {
    var gotExpectedReply = false
    var isStarting       = false
    var gotIdleStatus    = false
    (c, m) =>
      gotExpectedReply =
        gotExpectedReply || (c == Channel.Requests && m.header.msg_type == replyType)
      def incomingIdle =
        c == Channel.Publish &&
        m.header.msg_type == Status.messageType.messageType &&
        readFromArray(m.content.value)(Status.codec).execution_state == Status.idle.execution_state
      def incomingStarting =
        c == Channel.Publish &&
        m.header.msg_type == Status.messageType.messageType &&
        readFromArray(m.content.value)(Status.codec).execution_state ==
          Status.starting.execution_state
      gotIdleStatus = gotIdleStatus || (!isStarting && incomingIdle)
      isStarting = (isStarting && !incomingIdle) || incomingStarting
      IO.pure(gotExpectedReply && gotIdleStatus)
  }

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
    handler: MessageHandler = MessageHandler.discard { case _ => },
    trimReplyLines: Boolean = false
  )(implicit
    sessionId: SessionId,
    session: Session
  ): Unit = {

    val expectError0   = expectError || Option(errors).nonEmpty
    val ignoreStreams0 = ignoreStreams || Option(stdout).nonEmpty || Option(stderr).nonEmpty

    val input = Stream(
      executeMessage(code, stopOnError = !expectError0)
    )

    val stopWhen0: (Channel, Message[RawJson]) => IO[Boolean] =
      if (waitForUpdateDisplay)
        (_, m) => IO.pure(m.header.msg_type == "update_display_data")
      else
        stopWhen(ProtocolExecute.replyType.messageType)

    val streams = ClientStreams.create(input, stopWhen0, handler)

    session.run(streams)

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
      val controlMessageTypes         = streams.generatedMessageTypes(Set(Channel.Control)).toVector
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
      if (Properties.isWin) {
        val expectedStdoutLines = stdout.linesIterator.toVector
        val obtainedStdoutLines = stdoutMessages.linesIterator.toVector
        if (expectedStdoutLines != obtainedStdoutLines) {
          pprint.err.log(expectedStdoutLines)
          pprint.err.log(obtainedStdoutLines)
        }
        expect(expectedStdoutLines == obtainedStdoutLines)
      }
      else {
        if (stdout != stdoutMessages) {
          pprint.err.log(stdout)
          pprint.err.log(stdoutMessages)
        }
        expect(stdout == stdoutMessages)
      }
    }

    if (stderr != null) {
      val stderrMessages = streams.errorOutput.mkString
      if (Properties.isWin) {
        val expectedStderrLines = stderr.linesIterator.toVector
        val obtainedStderrLines = stderrMessages.linesIterator.toVector
        if (expectedStderrLines != obtainedStderrLines) {
          pprint.err.log(expectedStderrLines)
          pprint.err.log(obtainedStderrLines)
        }
        expect(expectedStderrLines == obtainedStderrLines)
      }
      else {
        if (stderr != stderrMessages) {
          val expectedStderr = stderr
          val obtainedStderr = stderrMessages
          pprint.err.log(expectedStderr)
          pprint.err.log(obtainedStderr)
        }
        expect(stderr == stderrMessages)
      }
    }

    val replies = streams.executeReplies
      .toVector
      .sortBy(_._1)
      .map(_._2)
      .map(s => if (trimReplyLines) s.trimLines else s)
    if (Properties.isWin) {
      expect(replies.length == Option(reply).toVector.length)
      val obtainedReplyLines =
        replies.headOption.iterator.flatMap(_.linesIterator).filter(_.nonEmpty).toVector
      val expectedReplyLines =
        Option(reply).iterator.flatMap(_.linesIterator).filter(_.nonEmpty).toVector
      if (obtainedReplyLines != expectedReplyLines) {
        pprint.err.log(obtainedReplyLines)
        pprint.err.log(expectedReplyLines)
      }
      expect(obtainedReplyLines == expectedReplyLines)
    }
    else {
      if (replies != Option(reply).toVector) {
        val expectedSingleReply = reply
        val gotReplies          = replies
        pprint.err.log(expectedSingleReply)
        pprint.err.log(gotReplies)
      }
      expect(replies == Option(reply).toVector)
    }

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

  def exit()(implicit
    sessionId: SessionId,
    session: Session
  ): Unit = {

    val input = Stream(
      // the comment makes the kernel exit in compile-only mode
      executeMessage("sys.exit(0) // ALMOND FORCE EXIT")
    )

    val streams = ClientStreams.create(input, stopWhen(ProtocolExecute.replyType.messageType))

    val interrupted =
      try {
        session.run(streams)
        false
      }
      catch {
        case _: InterruptedException =>
          true
      }

    if (!interrupted)
      sys.error("Expected to be interrupted")
  }

  final case class SessionId(sessionId: String = UUID.randomUUID().toString)

  private def executeMessage(
    code: String,
    msgId: String = UUID.randomUUID().toString,
    stopOnError: Boolean = true
  )(implicit sessionId: SessionId) =
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

  def inspect(
    code: String,
    pos: Int,
    detailed: Boolean
  )(implicit
    sessionId: SessionId,
    session: Session
  ): Seq[String] = {

    val input = Stream(
      inspectMessage(code, pos, detailed)
    )

    val streams = ClientStreams.create(input, stopWhen(Inspect.replyType.messageType))

    session.run(streams)

    streams.inspectRepliesHtml
  }

  private def inspectMessage(
    code: String,
    pos: Int,
    detailed: Boolean,
    msgId: String = UUID.randomUUID().toString
  )(implicit sessionId: SessionId) =
    Message(
      Header(
        msgId,
        "test",
        sessionId.sessionId,
        Inspect.requestType.messageType,
        Some(Protocol.versionStr)
      ),
      Inspect.Request(code, pos, if (detailed) 1 else 0)
    ).on(Channel.Requests)

  def complete(
    code: String,
    pos: Int = -1
  )(implicit
    sessionId: SessionId,
    session: Session
  ): Seq[Complete.Reply] = {

    val (code0, pos0) =
      if (pos >= 0) (code, pos)
      else {
        val cursor = "#"
        val idx    = code.indexOf(cursor)
        assert(idx >= 0, "Expected a # character in code to complete, at the cursor position")
        (code.take(idx) + code.drop(idx + cursor.length), idx)
      }

    val input = Stream(
      completeMessage(code0, pos0)
    )

    val streams = ClientStreams.create(input, stopWhen(Complete.replyType.messageType))

    session.run(streams)

    streams.completeReplies
  }

  private def completeMessage(
    code: String,
    pos: Int,
    msgId: String = UUID.randomUUID().toString
  )(implicit sessionId: SessionId) =
    Message(
      Header(
        msgId,
        "test",
        sessionId.sessionId,
        Complete.requestType.messageType,
        Some(Protocol.versionStr)
      ),
      Complete.Request(code, pos)
    ).on(Channel.Requests)

}
