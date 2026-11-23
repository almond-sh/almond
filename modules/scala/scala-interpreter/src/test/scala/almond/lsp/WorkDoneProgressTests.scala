package almond.lsp

import almond.interpreter.lsp.{LanguageServer => KernelLanguageServer}
import almond.logger.LoggerContext
import almond.protocol.RawJson
import com.google.gson.{JsonObject, JsonParser}
import org.eclipse.lsp4j._
import utest._

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

import scala.collection.mutable

object WorkDoneProgressTests extends TestSuite {

  Lsp4jLanguageServer.redirectLogging(LoggerContext.nop)

  /** Features whose tasks are set by tests */
  private final class Features extends AlmondLspFeatures {
    private def done[T](value: T) = CompletableFuture.completedFuture(value)
    def completion(at: UnitPosition): CompletableFuture[Seq[CompletionItem]] = done(Nil)
    def hover(at: UnitPosition): CompletableFuture[Option[Hover]]            = done(None)
    def signatureHelp(at: UnitPosition): CompletableFuture[Option[SignatureHelp]] =
      done(None)
    def definition(at: UnitPosition): CompletableFuture[Seq[SourceLocation]] = done(Nil)
    def references(
      at: UnitPosition,
      includeDeclaration: Boolean
    ): CompletableFuture[Seq[SourceLocation]] = done(Nil)
    def diagnostics(
      notebookUri: String,
      units: Seq[SourceUnit]
    ): CompletableFuture[Seq[(SourceUnit, Diagnostic)]] = done(Nil)

    var listeners = List.empty[Seq[LspTask] => Unit]
    def setTasks(tasks: Seq[LspTask]): Unit =
      listeners.foreach(_(tasks))
    override def onTasksChange(listener: Seq[LspTask] => Unit): AutoCloseable = {
      listeners = listener :: listeners
      listener(Nil)
      () => listeners = listeners.filter(_ ne listener)
    }
  }

  private final class Client extends KernelLanguageServer.Client {
    val received = mutable.ListBuffer.empty[JsonObject]
    def send(message: RawJson): Unit =
      received += JsonParser.parseString(message.toString).getAsJsonObject
    def close(): Unit = ()
  }

  private def msg(s: String) = RawJson(s.getBytes(StandardCharsets.UTF_8))

  private def setup(clientCapabilities: String) = {
    val sources =
      new SessionSources(SourceLayout(os.root / "session", Seq("ammonite", "$sess")))
    val features = new Features
    val server = new Lsp4jLanguageServer(
      () =>
        new AlmondLanguageServer(
          sources,
          features,
          Seq(new JupyterLabDocumentModel),
          LoggerContext.nop
        ),
      LoggerContext.nop
    )
    val client  = new Client
    val session = server.session(client)
    session.message(msg(
      s"""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":$clientCapabilities}}"""
    ))
    session.message(msg("""{"jsonrpc":"2.0","method":"initialized","params":{}}"""))
    client.received.clear()
    (features, client, session)
  }

  private def method(m: JsonObject): Option[String] =
    Option(m.get("method")).map(_.getAsString)

  val tests = Tests {

    test("reported") {
      val (features, client, session) = setup("""{"window":{"workDoneProgress":true}}""")

      val task = LspTask("indexer", "Indexing", Some("Resetting caches"))
      features.setTasks(Seq(task))
      val create = client.received.toList match {
        case List(m) if method(m).contains("window/workDoneProgress/create") => m
        case other => sys.error(s"Unexpected messages: $other")
      }
      val token = create.getAsJsonObject("params").get("token")
      // nothing until the client answered
      features.setTasks(Seq(task.copy(message = Some("Adding JDK source JARs to index"))))
      assert(client.received.length == 1)

      client.received.clear()
      session.message(msg(
        s"""{"jsonrpc":"2.0","id":${create.get("id")},"result":null}"""
      ))
      def values() =
        client.received.toList.map { m =>
          assert(method(m).contains("$/progress"))
          val params = m.getAsJsonObject("params")
          assert(params.get("token") == token)
          params.getAsJsonObject("value")
        }
      val (begin, report) = values() match {
        case List(b, r) => (b, r)
        case other      => sys.error(s"Unexpected progress values: $other")
      }
      assert(begin.get("kind").getAsString == "begin")
      assert(begin.get("title").getAsString == "Indexing")
      assert(begin.get("message").getAsString == "Resetting caches")
      assert(report.get("kind").getAsString == "report")
      assert(report.get("message").getAsString == "Adding JDK source JARs to index")

      client.received.clear()
      features.setTasks(Nil)
      val end = values() match {
        case List(e) => e
        case other   => sys.error(s"Unexpected progress values: $other")
      }
      assert(end.get("kind").getAsString == "end")
    }

    test("not supported by client") {
      val (features, client, _) = setup("{}")
      features.setTasks(Seq(LspTask("indexer", "Indexing")))
      assert(client.received.isEmpty)
      assert(features.listeners.isEmpty)
    }
  }
}
