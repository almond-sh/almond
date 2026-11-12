package almond.kernel

import almond.interpreter.{KernelSession, Message}
import almond.protocol.{Execute, History, RawJson, Status}
import almond.testkit.ClientStreams
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import utest._

object SpecLintTests extends TestSuite {

  private val session = KernelSession.create()

  private val request = Message.publish(
    KernelSession.create(),
    Execute.requestType,
    Execute.Request("1")
  )

  private def raw[T](message: Message[T])(implicit
    codec: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[T]
  ): Message[RawJson] =
    message.copy(content = RawJson(writeToArray(message.content)))

  val tests = Tests {

    test("valid messages") {
      val lint = new ClientStreams.SpecLint
      val messages = Seq(
        raw(Message.publish(session, Status.messageType, Status.starting)),
        raw(request.publish(session, Status.messageType, Status.busy)),
        raw(request.reply(session, Execute.replyType, Execute.Reply.Success(1, Map.empty, Nil))),
        raw(request.reply(session, History.replyType, History.Reply.Simple(Nil)))
      )
      for (m <- messages)
        assert(lint.issues(m).isEmpty)
    }

    test("session change") {
      val lint = new ClientStreams.SpecLint
      val first =
        lint.issues(raw(Message.publish(session, Status.messageType, Status.starting)))
      val other =
        lint.issues(raw(Message.publish(KernelSession.create(), Status.messageType, Status.idle)))
      assert(first.isEmpty)
      assert(other.exists(_.contains("differs from the one of former kernel messages")))
    }

    test("missing header fields") {
      val lint = new ClientStreams.SpecLint
      val m    = raw(Message.publish(session, Status.messageType, Status.idle))
      val m0 = m.copy(header =
        readFromArray(
          s"""{"msg_id":"${m.header.msg_id}","username":"","session":"${session.id}","msg_type":"status"}"""
            .getBytes("UTF-8")
        )(almond.protocol.Header.codec)
      )
      val issues = lint.issues(m0)
      assert(issues == Seq("no date in header", "no version in header"))
    }

    test("reply without status nor parent header") {
      val lint = new ClientStreams.SpecLint
      val reply =
        request.reply(session, Execute.replyType, Execute.Reply.Success(1, Map.empty, Nil))
      val m = reply
        .clearParentHeader
        .copy(content = RawJson("""{"execution_count":1}""".getBytes("UTF-8")))
      val issues = lint.issues(m)
      assert(issues == Seq("no parent header in reply", "no status in reply content"))
    }
  }

}
