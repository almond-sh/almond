package almond.lsp

import org.eclipse.lsp4j.{Position, Range}
import utest._

object UnitTextTests extends TestSuite {

  private def pos(line: Int, character: Int) = new Position(line, character)

  private val sources =
    new SessionSources(SourceLayout(os.root / "session", Seq("ammonite", "$sess")))
  sources.add(1, "cmd1", "val ran = 1", None)

  private val notebook = Notebook(
    "file:///work/nb.ipynb",
    1,
    Vector(
      CodeCell("val a = 1\nimport scala.collection.mutable"),
      CodeCell("val ran = 1", executionCount = Some(1)),
      CodeCell("val broken = ("),
      CodeCell("def f(n: Int) = n + a"),
      CodeCell("val b = f(2)\nb.to")
    )
  )

  // anything with an unclosed parenthesis doesn't parse here
  private def parses(code: String) = !code.contains("(\n") && !code.endsWith("(")

  val tests = Tests {

    test("pending units know about the pending cells before them") {
      val units = sources.units(notebook)
      assert(units(1).isInstanceOf[SourceUnit.Evaluated])
      val pending = units.collect { case p: SourceUnit.Pending => p }
      assert(pending.map(_.cell) == Seq(0, 2, 3, 4))
      assert(pending.map(_.previous.map(_.cell)) == Seq(Nil, Seq(0), Seq(0, 2), Seq(0, 2, 3)))
      assert(sources.unit(notebook, 3) == units(3))
    }

    test("text") {
      val unit = sources.unit(notebook, 4)
      val text = UnitText(unit, parses)
      val expected =
        """val a = 1
          |import scala.collection.mutable
          |final class almond$pending$cell3 {
          |def f(n: Int) = n + a
          |final class almond$pending$cell4 {
          |val b = f(2)
          |b.to
          |}
          |}""".stripMargin.replace("\r\n", "\n")
      assert(text.text == expected)

      test("positions") {
        assert(text.toText(pos(1, 2)) == pos(6, 2))
        val cell0 = sources.unit(notebook, 0)
        val cell3 = sources.unit(notebook, 3)
        assert(text.fromText(pos(6, 2)) == Some(UnitPosition(unit, pos(1, 2))))
        assert(text.fromText(pos(3, 4)) == Some(UnitPosition(cell3, pos(0, 4))))
        assert(text.fromText(pos(1, 7)) == Some(UnitPosition(cell0, pos(1, 7))))
        // the lines opening and closing the classes
        assert(text.fromText(pos(2, 0)).isEmpty)
        assert(text.fromText(pos(7, 0)).isEmpty)
        assert(text.unitRange(new Range(pos(5, 4), pos(5, 5))) == Some(
          new Range(pos(0, 4), pos(0, 5))
        ))
        assert(text.unitRange(new Range(pos(3, 4), pos(3, 5))).isEmpty)
      }
    }

    test("no pending cells before") {
      val unit = sources.unit(notebook, 0)
      val text = UnitText(unit, parses)
      assert(text.text == unit.code)
      assert(text.toText(pos(1, 3)) == pos(1, 3))
    }

    test("evaluated") {
      val unit = sources.unit(notebook, 1)
      val text = UnitText(unit, parses)
      assert(text.text == "val ran = 1")
      assert(text.fromText(pos(0, 4)) == Some(UnitPosition(unit, pos(0, 4))))
    }
  }
}
