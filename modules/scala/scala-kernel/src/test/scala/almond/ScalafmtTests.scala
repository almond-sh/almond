package almond

import java.util.concurrent.Executors

import almond.channels.{Channel, Message => RawMessage}
import almond.interpreter.Message
import almond.logger.{Level, LoggerContext}
import almond.protocol.{Header, RawJson}
import almond.protocol.custom.Format
import almond.util.ThreadUtil.daemonThreadFactory
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import utest._

import scala.collection.immutable.ListMap
import scala.concurrent.ExecutionContext
import almond.protocol.Status

object ScalafmtTests extends TestSuite {

  val fmtPool =
    ExecutionContext.fromExecutorService(coursier.cache.internal.ThreadUtil.fixedThreadPool(1))

  val queueEc = ExecutionContext.fromExecutorService(
    Executors.newSingleThreadExecutor(daemonThreadFactory("test-queue"))
  )

  override def utestAfterAll(): Unit = {
    fmtPool.shutdown()
    queueEc.shutdown()
  }

  def logCtx = almond.testkit.TestLogging.logCtx

  private def messages(
    scalafmt: Scalafmt,
    request: Format.Request
  ): Seq[(Channel, Message[RawJson])] = {
    val msg = Message(
      Header.random("jovian", Format.requestType),
      RawJson(writeToArray(request))
    )
    val messages = scalafmt.messageHandler.handle(Channel.Requests, msg) match {
      case None          => sys.error("format request left untouched")
      case Some(Left(e)) => throw new Exception("Error testing format request", e)
      case Some(Right(stream)) =>
        stream
          .compile
          .toVector
          .unsafeRunSync()(IORuntime.global)
          .map {
            case (c, m) =>
              val decoded = Message.parse[RawJson](m) match {
                case Left(e)   => throw new Exception(s"Error decoding $m", e)
                case Right(m0) => m0
              }
              (c, decoded)
          }
    }
    messages.filter {
      case (Channel.Publish, m) if m.header.msg_type == Status.messageType.messageType => false
      case _                                                                           => true
    }
  }

  private def endsWithFormatReply(messages: Seq[(Channel, Message[RawJson])])
    : Seq[(Channel, Message[RawJson])] = {
    assert(messages.nonEmpty)
    // FIXME Could publish / request messages be out-of-order here?
    val (responseChannel, response) = messages.last
    assert(responseChannel == Channel.Requests)
    assert(response.header.msg_type == Format.replyType.messageType)
    messages.init
  }

  private def onlyFormatResponses(messages: Seq[(Channel, Message[RawJson])])
    : Map[String, Format.Response] =
    messages
      .map {
        case (Channel.Publish, m) if m.header.msg_type == Format.responseType.messageType =>
          val resp = readFromArray[Format.Response](m.content.value)
          resp.key -> resp
        case (c, m) =>
          sys.error(s"Unexpected message ${m.header.msg_type} on channel $c ($m)")
      }
      .toMap

  val snippet1 =
    """def f(  n    :Int):    String
      |          =
      |   (                                     n+           1)                   .
      |                 toString
      |""".stripMargin
  val formattedSnippet1 =
    """def f(n: Int): String =
      |  (n + 1).toString""".stripMargin

  val snippet2 =
    """def g (n :Int) :String=
      |    ( n  +  2 ). toString
      |""".stripMargin
  val formattedSnippet2 =
    """def g(n: Int): String =
      |  (n + 2).toString""".stripMargin

  val scalafmt = new Scalafmt(
    fmtPool,
    queueEc,
    logCtx,
    "scala3"
  )

  val tests = Tests {

    test("empty") {
      val request         = Format.Request(ListMap())
      val messages0       = messages(scalafmt, request)
      val processMessages = endsWithFormatReply(messages0)
      assert(processMessages.isEmpty)
    }

    test("simple") {
      val initialCode  = snippet1
      val expectedCode = formattedSnippet1

      val request = Format.Request(ListMap(
        "cmd1" -> initialCode
      ))
      val messages0       = messages(scalafmt, request)
      val processMessages = endsWithFormatReply(messages0)
      assert(processMessages.length == 1)
      val formattedCodeMap = onlyFormatResponses(processMessages)
      println(formattedCodeMap)

      val resp = formattedCodeMap.getOrElse(
        "cmd1",
        sys.error("No data for key 'cmd1' in response")
      )
      assert(resp.initial_code == initialCode)
      val formattedCode = resp.code.getOrElse {
        sys.error(s"Formatting failed (no formatted code in response for input '$snippet1')")
      }
      assert(formattedCode == expectedCode)
    }

    test("multiple cells") {
      val request = Format.Request(ListMap(
        "cmd1" -> snippet1,
        "cmd2" -> snippet2
      ))
      val messages0       = messages(scalafmt, request)
      val processMessages = endsWithFormatReply(messages0)
      assert(processMessages.length == 2)
      val formattedCodeMap = onlyFormatResponses(processMessages)
      println(formattedCodeMap)

      val resp1 = formattedCodeMap.getOrElse(
        "cmd1",
        sys.error("No data for key 'cmd1' in response")
      )
      assert(resp1.initial_code == snippet1)
      val formattedCode1 = resp1.code.getOrElse {
        sys.error(s"Formatting failed (no formatted code in response for input '$snippet1')")
      }
      assert(formattedCode1 == formattedSnippet1)

      val resp2 = formattedCodeMap.getOrElse(
        "cmd2",
        sys.error("No data for key 'cmd2' in response")
      )
      assert(resp2.initial_code == snippet2)
      val formattedCode2 = resp2.code.getOrElse {
        sys.error(s"Formatting failed (no formatted code in response for input '$snippet2')")
      }
      assert(formattedCode2 == formattedSnippet2)
    }

  }

}
