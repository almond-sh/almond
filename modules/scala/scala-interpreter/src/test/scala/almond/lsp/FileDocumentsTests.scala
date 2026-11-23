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

object FileDocumentsTests extends TestSuite {

  Lsp4jLanguageServer.redirectLogging(LoggerContext.nop)

  // these are D:\session\... on Windows
  private val factory = os.root / "session" / "deps" / "Factory.scala"
  private val other   = os.root / "session" / "deps" / "Other.scala"

  private def done[T](value: T) = CompletableFuture.completedFuture(value)

  private final class Files extends FileFeatures {
    val calls                                        = mutable.ListBuffer.empty[String]
    def roots                                        = Seq(os.root / "session")
    def didOpen(path: os.Path, text: String): Unit   = calls += s"open $path"
    def didChange(path: os.Path, text: String): Unit = calls += s"change $path $text"
    def didClose(path: os.Path): Unit                = calls += s"close $path"
    def hover(path: os.Path, position: Position) = {
      calls += s"hover $path ${position.getLine}:${position.getCharacter}"
      done(Some(new Hover(new MarkupContent(MarkupKind.MARKDOWN, "hovered"))))
    }
    def signatureHelp(path: os.Path, position: Position) = done(None)
    def definition(path: os.Path, position: Position) = done(Seq(
      SourceLocation.External(
        new Location(other.toNIO.toUri.toASCIIString, new Range(pos(2, 0), pos(2, 3)))
      ),
      SourceLocation.External(
        new Location("file:///elsewhere/Other.scala", new Range(pos(1, 0), pos(1, 3)))
      )
    ))
    def references(path: os.Path, position: Position, includeDeclaration: Boolean) =
      done(Nil)
  }

  private final class Features(val files0: Files) extends AlmondLspFeatures {
    def completion(at: UnitPosition)    = done(Seq(new CompletionItem("foo")))
    def hover(at: UnitPosition)         = done(None)
    def signatureHelp(at: UnitPosition) = done(None)
    def definition(at: UnitPosition)    = done(Nil)
    def references(at: UnitPosition, includeDeclaration: Boolean) = done(Nil)
    def diagnostics(notebookUri: String, units: Seq[SourceUnit]) =
      done(Seq.empty[(SourceUnit, Diagnostic)])
    override def files: Option[FileFeatures] = Some(files0)
  }

  private def pos(line: Int, character: Int) = new Position(line, character)

  private final class Client extends KernelLanguageServer.Client {
    val received = mutable.ListBuffer.empty[JsonObject]
    def send(message: RawJson): Unit =
      received += JsonParser.parseString(message.toString).getAsJsonObject
    def close(): Unit = ()
  }

  private def msg(s: String) = RawJson(s.getBytes(StandardCharsets.UTF_8))

  val tests = Tests {
    val files    = new Files
    val sources  = new SessionSources(SourceLayout(os.root / "session", Seq("ammonite", "$sess")))
    val model    = new JupyterLabDocumentModel
    val features = new Features(files)
    val server = new Lsp4jLanguageServer(
      () => new AlmondLanguageServer(sources, features, Seq(model), LoggerContext.nop),
      LoggerContext.nop
    )
    val client  = new Client
    val session = server.session(client)
    def response(id: Int): JsonObject =
      client.received.find(m => m.has("id") && m.get("id").getAsInt == id).get

    session.message(msg(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}"""
    ))
    val uri = "file:///work/.lsp_symlink/session/deps/Factory.scala"
    session.message(msg(
      s"""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"$uri","languageId":"scala","version":1,"text":"object A"}}}"""
    ))

    test("opened") {
      assert(files.calls.toList == List(s"open $factory"))
      assert(model.notebooks.isEmpty)
    }

    test("hover") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","id":2,"method":"textDocument/hover","params":{"textDocument":{"uri":"$uri"},"position":{"line":3,"character":4}}}"""
      ))
      assert(files.calls.last == s"hover $factory 3:4")
      val value = response(2).getAsJsonObject("result").getAsJsonObject("contents").get("value")
      assert(value.getAsString == "hovered")
    }

    test("definition") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","id":3,"method":"textDocument/definition","params":{"textDocument":{"uri":"$uri"},"position":{"line":3,"character":4}}}"""
      ))
      val uris = {
        val arr = response(3).getAsJsonArray("result")
        (0 until arr.size()).map(arr.get(_).getAsJsonObject.get("uri").getAsString).toList
      }
      // files we handle are pointed to like the client opens them, the others as is
      assert(uris == List(
        "file:///work/.lsp_symlink" + other.toNIO.toUri.getRawPath,
        "file:///elsewhere/Other.scala"
      ))
    }

    test("no completion") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","id":4,"method":"textDocument/completion","params":{"textDocument":{"uri":"$uri"},"position":{"line":0,"character":1}}}"""
      ))
      assert(response(4).getAsJsonObject("result").getAsJsonArray("items").size() == 0)
    }

    test("closed") {
      session.message(msg(
        s"""{"jsonrpc":"2.0","method":"textDocument/didClose","params":{"textDocument":{"uri":"$uri"}}}"""
      ))
      assert(files.calls.last == s"close $factory")
    }

    test("other files are notebooks") {
      session.message(msg(
        """{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///work/.lsp_symlink/elsewhere/nb.ipynb","languageId":"scala","version":1,"text":"val a = 1\\n"}}}"""
      ))
      assert(model.notebooks.map(_.uri) == Seq("file:///work/.lsp_symlink/elsewhere/nb.ipynb"))
    }
  }
}
