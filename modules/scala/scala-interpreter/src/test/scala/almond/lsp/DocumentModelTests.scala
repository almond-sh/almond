package almond.lsp

import org.eclipse.lsp4j._
import utest._

import scala.jdk.CollectionConverters._

object DocumentModelTests extends TestSuite {

  private def uriOf(path: os.Path): String = path.toNIO.toUri.toASCIIString

  private def pos(line: Int, character: Int) = new Position(line, character)

  val tests = Tests {

    test("virtual document layout") {
      val cells = Vector(
        CodeCell("val a = 1"),
        CodeCell("def f(n: Int) =\n  n + a\n"),
        CodeCell("f(2)")
      )
      val text = VirtualDocumentLayout.text(cells)
      assert(text == "val a = 1\n\n\ndef f(n: Int) =\n  n + a\n\n\n\nf(2)\n")

      test("split") {
        val (splitCells, layout) = VirtualDocumentLayout.split(text)
        assert(splitCells == cells)
        assert(layout == VirtualDocumentLayout(cells))
        assert(layout.cellStartLines == Vector(0, 3, 8))
      }

      test("positions") {
        val layout = VirtualDocumentLayout(cells)
        assert(layout.cellPosition(pos(4, 6)) == Some(CellPosition(1, pos(1, 6))))
        assert(layout.position(CellPosition(1, pos(1, 6))) == pos(4, 6))
        assert(layout.cellPosition(pos(8, 1)) == Some(CellPosition(2, pos(0, 1))))
        // separators
        assert(layout.cellPosition(pos(1, 0)).isEmpty)
        assert(layout.cellPosition(pos(7, 0)).isEmpty)
      }
    }

    test("virtual document with header lines") {
      val prefix = JupyterLabDocumentModel.cellSeparatorPrefix
      val cells = Vector(
        CodeCell("val a = 1", Some("cell-a"), Some(1)),
        // empty lines don't split cells here
        CodeCell("def f(n: Int) =\n\n\n  n + a\n", Some("cell-f")),
        CodeCell("f(2)", Some("cell-f2"))
      )
      val text = VirtualDocumentLayout.textWithHeaders(cells, prefix)
      assert(
        text ==
          """//!! {"id":"cell-a","index":0,"cell_type":"code","lines":1,"execution_count":1}
            |val a = 1
            |//!! {"id":"cell-f","index":1,"cell_type":"code","lines":5}
            |def f(n: Int) =
            |
            |
            |  n + a
            |
            |//!! {"id":"cell-f2","index":2,"cell_type":"code","lines":1}
            |f(2)
            |""".stripMargin.replace("\r\n", "\n")
      )

      val (splitCells, layout) = VirtualDocumentLayout.splitWithHeaders(text, prefix).get
      assert(splitCells == cells)
      assert(layout.cellStartLines == Vector(1, 3, 9))
      assert(layout.cellLineCounts == Vector(1, 5, 1))
      assert(layout.cellPosition(pos(6, 2)) == Some(CellPosition(1, pos(3, 2))))
      assert(layout.position(CellPosition(2, pos(0, 1))) == pos(9, 1))
      // header lines
      assert(layout.cellPosition(pos(0, 3)).isEmpty)
      assert(layout.cellPosition(pos(8, 0)).isEmpty)

      test("non-code cells, unreadable headers") {
        val text =
          """//!! {"id":"md","index":0,"cell_type":"markdown","lines":1}
            |
            |//!! {"id":"c","index":1,"cell_type":"code","lines":2,"execution_count":null,"execution_state":"running","edited_since_run":false}
            |val a = 1
            |//!! not json
            |//!! {"id":"d","cell_type":"code"}
            |a + 1
            |a + 2
            |""".stripMargin.replace("\r\n", "\n")
        val (cells, layout) = VirtualDocumentLayout.splitWithHeaders(text, prefix).get
        assert(
          cells == Vector(
            CodeCell("val a = 1\n//!! not json", Some("c")),
            CodeCell("a + 1\na + 2", Some("d"))
          )
        )
        assert(layout.cellStartLines == Vector(3, 6))
      }

      test("no header lines") {
        assert(VirtualDocumentLayout.splitWithHeaders("val a = 1\n\n\nval b = 2\n", prefix).isEmpty)
        val model = new JupyterLabDocumentModel
        val opened = model.didOpen(new DidOpenTextDocumentParams(
          new TextDocumentItem("file:///nb.ipynb", "scala", 0, "val a = 1\n\n\nval b = 2\n")
        ))
        assert(opened.map(_.cells.map(_.code)) == Some(Vector("val a = 1", "val b = 2")))
      }

      test("model") {
        val model = new JupyterLabDocumentModel
        val uri   = "file:///work/nb.ipynb"
        val opened = model.didOpen(new DidOpenTextDocumentParams(
          new TextDocumentItem(uri, "scala", 0, text)
        ))
        assert(opened.map(_.cells) == Some(cells))
        val (notebook, cellPos) =
          model.cellPosition(new TextDocumentIdentifier(uri), pos(9, 1)).get
        assert(cellPos == CellPosition(2, pos(0, 1)))
        assert(
          model.location(notebook, CellRange(0, new Range(pos(0, 4), pos(0, 5)))) ==
            Some(new Location(uri, new Range(pos(1, 4), pos(1, 5))))
        )
      }
    }

    test("consecutive empty lines in a cell split it") {
      val (cells, _) = VirtualDocumentLayout.split("val a = 1\n\n\nval b = 2\n")
      assert(cells.map(_.code) == Vector("val a = 1", "val b = 2"))
    }

    test("model") {
      val model = new JupyterLabDocumentModel
      val uri   = "file:///work/nb.ipynb"
      val doc   = new TextDocumentIdentifier(uri)

      val opened = model.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(uri, "scala", 1, "val a = 1\n\n\nval b = a\n")
      ))
      assert(opened.map(_.cells.map(_.code)) == Some(Vector("val a = 1", "val b = a")))

      // incremental change, adding a cell
      val change = new TextDocumentContentChangeEvent(
        new Range(pos(3, 9), pos(3, 9)),
        "\n\n\nb + 1"
      )
      val changed = model.didChange(new DidChangeTextDocumentParams(
        new VersionedTextDocumentIdentifier(uri, 2),
        List(change).asJava
      ))
      assert(changed.map(_.cells.map(_.code)) == Some(Vector("val a = 1", "val b = a", "b + 1")))
      assert(changed.map(_.version) == Some(2))

      val (notebook, cellPos) = model.cellPosition(doc, pos(6, 1)).get
      assert(cellPos == CellPosition(2, pos(0, 1)))
      assert(
        model.location(notebook, CellRange(1, new Range(pos(0, 4), pos(0, 5)))) ==
          Some(new Location(uri, new Range(pos(3, 4), pos(3, 5))))
      )

      assert(model.didClose(new DidCloseTextDocumentParams(doc)) == Some(uri))
      assert(model.notebooks.isEmpty)
    }

    test("session sources") {
      val sessionDir = os.root / "session"
      val sources    = new SessionSources(SourceLayout(sessionDir, Seq("ammonite", "$sess")))
      sources.add(1, "cmd1", "val a = 1", None)
      sources.add(2, "cmd2", "val b = a", Some("cell-b"))

      val notebook = Notebook(
        "file:///nb.ipynb",
        1,
        Vector(CodeCell("val a = 1"), CodeCell("val b = a + 1"), CodeCell("val b = a"))
      )
      val units = sources.units(notebook)
      units(0) match {
        case SourceUnit.Evaluated(input) =>
          assert(input.wrapperName == "cmd1")
          assert(
            input.source.uri == uriOf(sessionDir / "sources" / "ammonite" / "$sess" / "cmd1.sc")
          )
        case other => throw new Exception(s"Unexpected unit $other")
      }
      assert(units(1).isInstanceOf[SourceUnit.Pending])
      assert(units(2).isInstanceOf[SourceUnit.Evaluated])
      assert(units.indices.forall(idx => sources.cell(notebook, units(idx)) == Some(idx)))

      test("cell ids") {
        val withIds = notebook.copy(cells =
          notebook.cells.updated(2, CodeCell("val b = a", id = Some("other-cell")))
        )
        assert(sources.unit(withIds, 2).isInstanceOf[SourceUnit.Pending])
      }

      test("changed cells") {
        val changed = notebook.copy(cells = notebook.cells.updated(0, CodeCell("val a = 2")))
        assert(sources.cell(changed, units(0)).isEmpty)
      }
    }

    test("wrapped source") {
      val wrapped = WrappedSource("file:///cmd1.scala", topWrapperLines = 5, userCodeLines = 2)
      assert(wrapped.toWrapped(pos(1, 3)) == pos(6, 3))
      assert(wrapped.fromWrapped(pos(6, 3)) == Some(pos(1, 3)))
      assert(wrapped.fromWrapped(pos(4, 0)).isEmpty)
      assert(wrapped.fromWrapped(pos(7, 0)).isEmpty)
    }
  }
}
