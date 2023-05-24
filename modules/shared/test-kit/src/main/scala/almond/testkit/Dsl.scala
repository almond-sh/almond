package almond.testkit

import almond.channels.Channel
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.protocol.{Execute => ProtocolExecute, _}
import cats.effect.IO
import com.eed3si9n.expecty.Expecty.expect
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import fs2.Stream

import java.nio.charset.StandardCharsets
import java.util.UUID

object Dsl {

  trait Runner {
    def apply(options: String*): Session
    def withExtraClassPath(extraClassPath: String*)(options: String*): Session
    def withLauncherOptions(launcherOptions: String*)(options: String*): Session

    def withSession[T](options: String*)(f: Session => T): T
    def withExtraClassPathSession[T](extraClassPath: String*)(options: String*)(f: Session => T): T
    def withLauncherOptionsSession[T](launcherOptions: String*)(options: String*)(f: Session => T)
      : T

    def differedStartUp: Boolean = false
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

    val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] =
      if (waitForUpdateDisplay)
        (_, m) => IO.pure(m.header.msg_type == "update_display_data")
      else {
        var gotExecuteReply = false
        var gotIdleStatus   = false
        (c, m) =>
          gotExecuteReply = gotExecuteReply ||
            (c == Channel.Requests && m.header.msg_type == "execute_reply")
          gotIdleStatus = gotIdleStatus || (
            c == Channel.Publish && m.header.msg_type == "status" && readFromArray(m.content.value)(
              Status.codec
            ).execution_state == "idle"
          )
          IO.pure(gotExecuteReply && gotIdleStatus)
      }

    val streams = ClientStreams.create(input, stopWhen, handler)

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
      expect(stdout == stdoutMessages)
    }

    if (stderr != null) {
      val stderrMessages = streams.errorOutput.mkString
      expect(stderr == stderrMessages)
    }

    val replies = streams.executeReplies
      .toVector
      .sortBy(_._1)
      .map(_._2)
      .map(s => if (trimReplyLines) s.trimLines else s)
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

    val stopWhen: (Channel, Message[RawJson]) => IO[Boolean] = {
      var gotInspectReply = false
      var gotIdleStatus   = false
      (c, m) =>
        gotInspectReply = gotInspectReply ||
          (c == Channel.Requests && m.header.msg_type == Inspect.replyType.messageType)
        gotIdleStatus = gotIdleStatus || (
          c == Channel.Publish && m.header.msg_type == Status.messageType.messageType && readFromArray(
            m.content.value
          )(
            Status.codec
          ).execution_state == Status.idle.execution_state
        )
        IO.pure(gotInspectReply && gotIdleStatus)
    }

    val streams = ClientStreams.create(input, stopWhen)

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

}
