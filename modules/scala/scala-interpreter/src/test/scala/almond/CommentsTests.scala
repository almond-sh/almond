package almond

import almond.internals.Comments
import utest._

object CommentsTests extends TestSuite {

  // '|' marks the position to check in code (and is removed from it)
  private def inComment(code: String): Boolean = {
    val pos = code.indexOf('|')
    assert(pos >= 0)
    Comments.isInComment(code.take(pos) + code.drop(pos + 1), pos)
  }

  val tests = Tests {

    test("line comments") {
      assert(inComment("// List(1).ma|"))
      assert(inComment("//|"))
      assert(inComment("//> using de|"))
      assert(inComment("val n = 2 // n|\nval m = 3"))
      assert(inComment("val n = 2 // n|"))
      assert(!inComment("|// foo"))
      assert(!inComment("val n = 2 // n\nval m = n|"))
      assert(!inComment("val n = 2 // n\n|"))
      assert(!inComment("List(1).ma|"))
    }

    test("block comments") {
      assert(inComment("/* foo|"))
      assert(inComment("/* foo| */ val n = 2"))
      assert(inComment("/** foo\n  * bar|\n  */"))
      assert(inComment("/* a /* nested */ b| */"))
      assert(!inComment("/* a /* nested */ b */ c|"))
      assert(!inComment("/* foo */|"))
      assert(!inComment("|/* foo */"))
      assert(!inComment("val n = 2 /* foo */ + n|"))
    }

    test("strings") {
      assert(!inComment("\"// foo|\""))
      assert(!inComment("\"// foo\".len|"))
      assert(inComment("\"// foo\" // len|"))
      assert(!inComment("\"\\\" // foo|\""))
      assert(!inComment("\"\"\"a\n// b|\"\"\""))
      assert(inComment("\"\"\"a\n// b\"\"\" // c|"))
      assert(!inComment("\"\"\"a\"\"\"\" + b|"))
      assert(!inComment("\"/* foo\" + b|"))
      // unclosed strings end with their line
      assert(inComment("\"foo\n// b|"))
    }

    test("interpolated strings") {
      assert(!inComment("s\"// ${n}|\""))
      assert(!inComment("s\"// ${n.toS|}\""))
      assert(!inComment("s\"${\"}\"} // foo|\""))
      assert(inComment("s\"${n /* foo| */}\""))
      assert(inComment("s\"${n}\" // foo|"))
      assert(inComment("s\"\"\"${Map(1 -> 2).map { case (a, b) => a }}\"\"\" // foo|"))
      assert(!inComment("s\"$$// foo|\""))
    }

    test("character literals and backticks") {
      assert(!inComment("'\"' + 1|"))
      assert(inComment("'\"' // 1|"))
      assert(!inComment("'\\'' + 1|"))
      assert(!inComment("val `a//b` = 1|"))
      assert(inComment("val `a//b` = 1 // c|"))
      assert(!inComment("'foo // comment\nval n = 2|"))
    }
  }
}
