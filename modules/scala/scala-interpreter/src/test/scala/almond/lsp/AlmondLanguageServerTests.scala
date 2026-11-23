package almond.lsp

import almond.interpreter.lsp.{LanguageServer => KernelLanguageServer}
import almond.logger.LoggerContext
import almond.protocol.RawJson
import com.google.gson.{JsonObject, JsonParser}
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import utest._

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

import scala.collection.mutable

object AlmondLanguageServerTests extends TestSuite {

  private def uriOf(path: os.Path): String = path.toNIO.toUri.toASCIIString

  // like ScalaKernel does
  Lsp4jLanguageServer.redirectLogging(LoggerContext.nop)

  private def pos(line: Int, character: Int) = new Position(line, character)

  /** Features recording their calls, and returning ranges on the requested units */
  private final class RecordingFeatures extends AlmondLspFeatures {
    val calls                     = mutable.ListBuffer.empty[UnitPosition]
    private def done[T](value: T) = CompletableFuture.completedFuture(value)

    def completion(at: UnitPosition): CompletableFuture[Seq[CompletionItem]] = {
      calls += at
      val item = new CompletionItem("foo")
      val p    = at.position
      item.setTextEdit(JEither.forLeft(new TextEdit(new Range(pos(p.getLine, 0), p), "foo")))
      done(Seq(item))
    }
    def hover(at: UnitPosition): CompletableFuture[Option[Hover]] = done(None)
    def signatureHelp(at: UnitPosition): CompletableFuture[Option[SignatureHelp]] =
      done(None)
    def definition(at: UnitPosition): CompletableFuture[Seq[SourceLocation]] = {
      calls += at
      // definitions in the first cell, and in an input that isn't in the notebook
      val firstCellUnit = sources.units(currentNotebook()).head
      val otherInput    = sources.inputs.find(_.wrapperName == "cmd5").get
      done(Seq(
        SourceLocation.InUnit(UnitRange(firstCellUnit, new Range(pos(0, 4), pos(0, 5)))),
        SourceLocation.InUnit(
          UnitRange(SourceUnit.Evaluated(otherInput), new Range(pos(0, 4), pos(0, 7)))
        )
      ))
    }
    def references(
      at: UnitPosition,
      includeDeclaration: Boolean
    ): CompletableFuture[Seq[SourceLocation]] = done(Nil)
    // kept and returned again, like PlasmonFeatures does with the diagnostics of Plasmon
    private val diagnostic = new Diagnostic(new Range(pos(0, 0), pos(0, 3)), "oops")
    def diagnostics(
      notebookUri: String,
      units: Seq[SourceUnit]
    ): CompletableFuture[Seq[(SourceUnit, Diagnostic)]] =
      done(Seq(units.last -> diagnostic))

    var currentNotebook: () => Notebook = () => sys.error("not set")
  }

  private val sources =
    new SessionSources(SourceLayout(os.root / "session", Seq("ammonite", "$sess")))
  sources.add(1, "cmd1", "val a = 1", None)
  sources.add(5, "cmd5", "val other = 2", None)

  private final class Client extends KernelLanguageServer.Client {
    val received = mutable.ListBuffer.empty[JsonObject]
    def send(message: RawJson): Unit =
      received += JsonParser.parseString(message.toString).getAsJsonObject
    def close(): Unit = ()
  }

  private def msg(s: String) = RawJson(s.getBytes(StandardCharsets.UTF_8))

  val tests = Tests {
    val features = new RecordingFeatures
    val model    = new JupyterLabDocumentModel
    val server = new Lsp4jLanguageServer(
      () => new AlmondLanguageServer(sources, features, Seq(model), LoggerContext.nop),
      LoggerContext.nop
    )
    features.currentNotebook = () => model.notebooks.head

    val client  = new Client
    val session = server.session(client)

    def response(id: Int): JsonObject =
      client.received.find(m => m.has("id") && m.get("id").getAsInt == id).get

    session.message(msg(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}"""
    ))
    val capabilities = response(1).getAsJsonObject("result").getAsJsonObject("capabilities")
    assert(capabilities.get("hoverProvider").getAsBoolean)

    val uri  = "file:///work/nb.ipynb"
    val text = "val a = 1\\n\\n\\nval b = a\\nval c = b\\n"
    session.message(msg(
      s"""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"$uri","languageId":"scala","version":1,"text":"$text"}}}"""
    ))

    test("diagnostics") {
      val notification = client.received.find(_.has("method")).get
      assert(notification.get("method").getAsString == "textDocument/publishDiagnostics")
      val params = notification.getAsJsonObject("params")
      assert(params.get("uri").getAsString == uri)
      val range = params.getAsJsonArray("diagnostics").get(0).getAsJsonObject
        .getAsJsonObject("range")
      assert(range.getAsJsonObject("start").get("line").getAsInt == 3)
    }

    test("diagnostics published again") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":"$uri","version":2},"contentChanges":[{"text":"$text"}]}}"""
      ))
      val lines = client.received.toList
        .filter(m =>
          m.has("method") && m.get("method").getAsString == "textDocument/publishDiagnostics"
        )
        .map { m =>
          m.getAsJsonObject("params").getAsJsonArray("diagnostics").get(0).getAsJsonObject
            .getAsJsonObject("range").getAsJsonObject("start").get("line").getAsInt
        }
      assert(lines == List(3, 3))
    }

    test("completion") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","id":2,"method":"textDocument/completion","params":{"textDocument":{"uri":"$uri"},"position":{"line":4,"character":9}}}"""
      ))
      val at = features.calls.head
      assert(at.position == pos(1, 9))
      assert(at.unit.isInstanceOf[SourceUnit.Pending])
      assert(at.unit.code == "val b = a\nval c = b")

      val item = response(2).getAsJsonObject("result").getAsJsonArray("items").get(0)
        .getAsJsonObject
      val editRange = item.getAsJsonObject("textEdit").getAsJsonObject("range")
      assert(editRange.getAsJsonObject("start").get("line").getAsInt == 4)
      assert(editRange.getAsJsonObject("end").get("character").getAsInt == 9)
    }

    test("definition") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","id":3,"method":"textDocument/definition","params":{"textDocument":{"uri":"$uri"},"position":{"line":3,"character":8}}}"""
      ))
      val locations  = response(3).getAsJsonArray("result")
      val inNotebook = locations.get(0).getAsJsonObject
      assert(inNotebook.get("uri").getAsString == uri)
      assert(
        inNotebook.getAsJsonObject("range").getAsJsonObject("start").get("line").getAsInt == 0
      )
      val inOtherInput = locations.get(1).getAsJsonObject
      assert(
        inOtherInput.get("uri").getAsString ==
          uriOf(os.root / "session" / "sources" / "ammonite" / "$sess" / "cmd5.sc")
      )
    }

    test("outside of cells") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","id":4,"method":"textDocument/completion","params":{"textDocument":{"uri":"$uri"},"position":{"line":1,"character":0}}}"""
      ))
      assert(features.calls.isEmpty)
      assert(response(4).getAsJsonObject("result").getAsJsonArray("items").size() == 0)
    }

    test("unsupported request") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","id":5,"method":"textDocument/formatting","params":{"textDocument":{"uri":"$uri"},"options":{"tabSize":2,"insertSpaces":true}}}"""
      ))
      assert(response(5).has("error"))
    }
  }
}
