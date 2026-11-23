package almond.interpreter.lsp

import almond.interpreter.api.{CommHandler, CommTarget, DisplayData}
import almond.logger.LoggerContext
import almond.protocol.RawJson
import utest._

import java.nio.charset.StandardCharsets

import scala.collection.mutable

object LspCommTargetTests extends TestSuite {

  private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)
  private def str(b: Array[Byte]): String   = new String(b, StandardCharsets.UTF_8)

  private final class RecordingCommHandler extends CommHandler {
    val targets  = mutable.Map.empty[String, CommTarget]
    val messages = mutable.ListBuffer.empty[(String, String)]
    val closed   = mutable.ListBuffer.empty[String]
    val ids      = mutable.Set.empty[String]

    def registerCommTarget(name: String, target: CommTarget): Unit = targets(name) = target
    def unregisterCommTarget(name: String): Unit                   = targets -= name
    def registerCommId(id: String, target: CommTarget): Unit       = ids += id
    def unregisterCommId(id: String): Unit                         = ids -= id
    def commOpen(targetName: String, id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
      ()
    def commMessage(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
      messages += id -> str(data)
    def commClose(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
      closed += id
    def updateDisplay(displayData: DisplayData): Unit = ()
  }

  private final class RecordingServer extends LanguageServer {
    val clients  = mutable.ListBuffer.empty[LanguageServer.Client]
    val received = mutable.ListBuffer.empty[(Int, String)]
    val closed   = mutable.ListBuffer.empty[Int]
    def session(client: LanguageServer.Client): LanguageServer.Session = {
      val idx = clients.length
      clients += client
      new LanguageServer.Session {
        def message(message: RawJson): Unit = received += idx -> message.toString
        def close(): Unit                   = closed += idx
      }
    }
  }

  val tests = Tests {

    test("sessions") {
      val commHandler = new RecordingCommHandler
      val server      = new RecordingServer
      LspCommTarget.register(server, commHandler, LoggerContext.nop)
      val target = commHandler.targets(LanguageServer.commTarget)

      target.open("a", bytes("{}"))
      target.open("b", bytes("{}"))
      target.message("a", bytes("""{"message":{"jsonrpc":"2.0","id":1,"method":"initialize"}}"""))
      target.message("b", bytes("""{"message":{"jsonrpc":"2.0","method":"initialized"}}"""))
      target.message("a", bytes("""{"other":2}"""))
      target.message("unknown", bytes("""{"message":{}}"""))

      assert(
        server.received.toList == List(
          0 -> """{"jsonrpc":"2.0","id":1,"method":"initialize"}""",
          1 -> """{"jsonrpc":"2.0","method":"initialized"}"""
        )
      )

      server.clients(1).send(RawJson(bytes("""{"jsonrpc":"2.0","id":3,"result":null}""")))
      assert(
        commHandler.messages.toList == List(
          "b" -> """{"message":{"jsonrpc":"2.0","id":3,"result":null}}"""
        )
      )

      target.close("a", bytes("{}"))
      assert(server.closed.toList == List(0))

      server.clients(1).close()
      assert(commHandler.closed.toList == List("b"))
      server.clients(1).close()
      assert(commHandler.closed.toList == List("b"))
      target.message("b", bytes("""{"message":{}}"""))
      assert(server.received.length == 2)
    }

  }
}
