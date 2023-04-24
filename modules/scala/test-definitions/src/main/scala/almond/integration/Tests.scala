package almond.integration

import almond.channels.Channel
import almond.interpreter.Message
import almond.interpreter.messagehandlers.MessageHandler
import almond.protocol.{Execute => ProtocolExecute, _}
import almond.testkit.Dsl._

import java.util.UUID

object Tests {

  private val sp = " "
  private val ls = System.lineSeparator()

  private def maybePostImportNewLine(isScala2: Boolean) =
    if (isScala2) "" else System.lineSeparator()

  def jvmRepr()(implicit sessionId: SessionId, runner: Runner): Unit = {
    implicit val session: Session = runner()
    execute("""class Bar(val value: String)""", "defined class Bar")
    execute(
      """kernel.register[Bar](bar => Map("text/plain" -> s"Bar(${bar.value})"))""",
      ""
    )
    execute(
      """val b = new Bar("other")""",
      "",
      displaysText = Seq("Bar(other)")
    )
  }

  def updatableDisplay()(implicit sessionId: SessionId, runner: Runner): Unit = {
    implicit val session: Session = runner()
    execute(
      """val handle = Html("<b>foo</b>")""",
      "",
      displaysHtml = Seq("<b>foo</b>")
    )

    execute(
      """handle.withContent("<i>bzz</i>").update()""",
      "",
      displaysHtmlUpdates = Seq("<i>bzz</i>")
    )
  }

  def autoUpdateFutureUponCompletion(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit = {

    val isScala2   = scalaVersion.startsWith("2.")
    val isScala212 = scalaVersion.startsWith("2.12.")

    implicit val session: Session = runner()

    execute(
      "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global",
      // Multi-line with stripMargin seems to be a problem on our Windows CI for this test,
      // but not for the other ones using stripMargin…
      s"import scala.concurrent.Future;$sp$ls" +
        s"import scala.concurrent.ExecutionContext.Implicits.global${maybePostImportNewLine(isScala2)}"
    )

    execute(
      "val f = Future { Thread.sleep(3000L); 2 }",
      "",
      displaysText = Seq("f: Future[Int] = [running]")
    )

    execute(
      "Thread.sleep(6000L)",
      "",
      // the update originates from the previous cell, but arrives while the third one is running
      displaysTextUpdates = Seq(
        if (isScala212) "f: Future[Int] = Success(2)"
        else "f: Future[Int] = Success(value = 2)"
      )
    )
  }

  def autoUpdateFutureInBackgroundUponCompletion(scalaVersion: String)(implicit
    sessionId: SessionId,
    runner: Runner
  ): Unit = {

    // same as above, except no cell is running when the future completes

    val isScala2   = scalaVersion.startsWith("2.")
    val isScala212 = scalaVersion.startsWith("2.12.")

    implicit val session: Session = runner()

    execute(
      "import scala.concurrent.Future; import scala.concurrent.ExecutionContext.Implicits.global",
      // Multi-line with stripMargin seems to be a problem on our Windows CI for this test,
      // but not for the other ones using stripMargin…
      s"import scala.concurrent.Future;$sp$ls" +
        s"import scala.concurrent.ExecutionContext.Implicits.global${maybePostImportNewLine(isScala2)}"
    )

    execute(
      "val f = Future { Thread.sleep(3000L); 2 }",
      "",
      displaysText = Seq("f: Future[Int] = [running]"),
      displaysTextUpdates = Seq(
        if (isScala212) "f: Future[Int] = Success(2)"
        else "f: Future[Int] = Success(value = 2)"
      ),
      waitForUpdateDisplay = true
    )
  }

  def autoUpdateRxStuffUponChange()(implicit sessionId: SessionId, runner: Runner): Unit = {
    implicit val session: Session = runner()

    execute(
      "almondrx.setup()",
      "",
      ignoreStreams = true
    )

    execute(
      "val a = rx.Var(1)",
      "",
      displaysText = Seq(
        "a: rx.Var[Int] = 1"
      )
    )

    execute(
      "a() = 2",
      "",
      displaysTextUpdates = Seq(
        "a: rx.Var[Int] = 2"
      )
    )

    execute(
      "a() = 3",
      "",
      displaysTextUpdates = Seq(
        "a: rx.Var[Int] = 3"
      )
    )
  }

  def handleInterruptMessages()(implicit sessionId: SessionId, runner: Runner): Unit = {

    val interruptOnInput = MessageHandler(Channel.Input, Input.requestType) { msg =>
      Message(
        Header(
          UUID.randomUUID().toString,
          "test",
          sessionId.sessionId,
          Interrupt.requestType.messageType,
          Some(Protocol.versionStr)
        ),
        Interrupt.Request
      ).streamOn(Channel.Control)
    }

    val ignoreExpectedReplies = MessageHandler.discard {
      case (Channel.Publish, _)                                                                =>
      case (Channel.Requests, m) if m.header.msg_type == ProtocolExecute.replyType.messageType =>
      case (Channel.Control, m) if m.header.msg_type == Interrupt.replyType.messageType        =>
    }

    implicit val session: Session = runner()

    execute(
      "val n = scala.io.StdIn.readInt()",
      ignoreStreams = true,
      expectError = true,
      expectInterrupt = true,
      handler = interruptOnInput.orElse(ignoreExpectedReplies)
    )

    execute(
      """val s = "ok done"""",
      """s: String = "ok done""""
    )
  }

  def exit()(implicit sessionId: SessionId, runner: Runner): Unit = {
    implicit val session: Session = runner()

    execute(
      "val n = 2",
      "n: Int = 2"
    )

    execute(
      "exit",
      "",
      replyPayloads = Seq(
        """{"source":"ask_exit","keepkernel":false}"""
      )
    )
  }

  def trapOutput()(implicit sessionId: SessionId, runner: Runner): Unit = {
    implicit val session: Session = runner()

    execute(
      "val n = 2",
      "n: Int = 2"
    )

    execute(
      """println("Hello")""",
      "",
      stdout = "",
      stderr = ""
    )

    execute(
      """System.err.println("Bbbb")""",
      "",
      stdout = "",
      stderr = ""
    )

    execute(
      "exit",
      "",
      stdout = "",
      stderr = ""
    )
  }

  def lastException()(implicit sessionId: SessionId, runner: Runner): Unit = {
    implicit val session: Session = runner()

    execute(
      """val nullBefore = repl.lastException == null""",
      "nullBefore: Boolean = true"
    )
    execute("""sys.error("foo")""", expectError = true)
    execute("""val nullAfter = repl.lastException == null""", "nullAfter: Boolean = false")
  }

  def history()(implicit sessionId: SessionId, runner: Runner): Unit = {
    implicit val session: Session = runner()

    execute(
      """val before = repl.history.toVector""",
      """before: Vector[String] = Vector("val before = repl.history.toVector")"""
    )
    execute("val a = 2", "a: Int = 2")
    execute("val b = a + 1", "b: Int = 3")
    execute(
      """val after = repl.history.toVector.mkString(",").toString""",
      """after: String = "val before = repl.history.toVector,val a = 2,val b = a + 1,val after = repl.history.toVector.mkString(\",\").toString""""
    )
  }

}
