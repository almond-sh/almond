package almond

import almond.internals.DirectiveCompletion
import almond.internals.DirectiveCompletion.DependencyValue
import utest._

object DirectiveCompletionTests extends TestSuite {

  // the cursor position is the one of the '|' in code, that is removed
  private def find(code: String): Option[DependencyValue] = {
    val pos = code.indexOf('|')
    assert(pos >= 0)
    DirectiveCompletion.findDependency(code.take(pos) + code.drop(pos + 1), pos)
  }

  val tests = Tests {

    test("quoted") {
      val res = find("""//> using dep "org.typelevel::cats-co|""")
      assert(res == Some(DependencyValue(15, "org.typelevel::cats-co")))
    }

    test("quoted closed") {
      val res = find("""//> using dep "org.typelevel::cats-co|"""")
      assert(res == Some(DependencyValue(15, "org.typelevel::cats-co")))
    }

    test("bare") {
      val res = find("//> using dep org.typelevel::cats-core:2.1|")
      assert(res == Some(DependencyValue(14, "org.typelevel::cats-core:2.1")))
    }

    test("cursor in the middle of a value") {
      val res = find("//> using dep org.typelevel::cats|-core:2.12.0")
      assert(res == Some(DependencyValue(14, "org.typelevel::cats")))
    }

    test("aliases") {
      for (key <- Seq("deps", "dependencies", "lib", "libs")) {
        val res = find(s"//> using $key org.typelevel::cats-co|")
        assert(res == Some(DependencyValue(11 + key.length, "org.typelevel::cats-co")))
      }
    }

    test("several values") {
      val res = find("""//> using deps "com.lihaoyi::os-lib:0.11.4", "org.typelevel::cats-co|""")
      assert(res == Some(DependencyValue(46, "org.typelevel::cats-co")))
      val res0 = find("//> using deps com.lihaoyi::os-lib:0.11.4, org.typelevel::cats-co|")
      assert(res0 == Some(DependencyValue(43, "org.typelevel::cats-co")))
    }

    test("not in a value yet") {
      val res = find("//> using dep |")
      assert(res == Some(DependencyValue(14, "")))
      val res0 = find("""//> using dep "com.lihaoyi::os-lib:0.11.4"|""")
      assert(res0 == Some(DependencyValue(42, "")))
    }

    test("later directive line") {
      val code =
        """// a comment
          |//> using option -deprecation
          |
          |//> using dep org.typelevel::cats-co|
          |
          |val n = 2
          |""".stripMargin
      val res   = find(code)
      val start = code.indexOf("org.typelevel")
      assert(res == Some(DependencyValue(start, "org.typelevel::cats-co")))
    }

    test("extra spaces") {
      val res = find("//>   using   dep   org.typelevel::cats-co|")
      assert(res == Some(DependencyValue(20, "org.typelevel::cats-co")))
    }

    test("other directives") {
      assert(find("//> using option -depre|").isEmpty)
      assert(find("//> using repository jitp|").isEmpty)
    }

    test("directive key") {
      assert(find("//> using de|").isEmpty)
    }

    test("after code") {
      val code =
        """val n = 2
          |//> using dep org.typelevel::cats-co|
          |""".stripMargin
      assert(find(code).isEmpty)
    }

    test("right after a colon") {
      val res = find("//> using dep org.typelevel:|")
      assert(res == Some(DependencyValue(14, "org.typelevel:")))
      val res0 = find("""//> using dep "org.typelevel::cats-core:|"""")
      assert(res0 == Some(DependencyValue(15, "org.typelevel::cats-core:")))
    }

    test("is version") {
      val versions = Seq(
        "org.typelevel:cats-core_2.13:",
        "org.typelevel:cats-core_2.13:2.1",
        "org.typelevel::cats-core:",
        "org.typelevel::cats-core:2.1",
        "org.scala-lang:::scala-compiler:"
      )
      val notVersions = Seq(
        "",
        "org.type",
        "org.typelevel:",
        "org.typelevel:cats-co",
        "org.typelevel::",
        "org.typelevel::cats-co",
        "org.typelevel:::",
        "org.typelevel:::cats-co"
      )
      for (input <- versions)
        assert(DirectiveCompletion.isVersion(input))
      for (input <- notVersions)
        assert(!DirectiveCompletion.isVersion(input))
    }

    test("sort versions") {
      val versions =
        Seq("2.0.0-M4", "2.0.0-RC1", "2.0.0", "2.10.0", "2.9.0", "2.13.0", "2.13.0-RC1", "2.9.0")
      val res = DirectiveCompletion.sortCompletions("org.typelevel::cats-core:", versions)
      val expected =
        Seq("2.13.0", "2.13.0-RC1", "2.10.0", "2.9.0", "2.0.0", "2.0.0-RC1", "2.0.0-M4")
      assert(res == expected)
    }

    test("don't sort names") {
      val names = Seq("cats-free", "cats-core", "cats-effect")
      assert(DirectiveCompletion.sortCompletions("org.typelevel::cats-", names) == names)
      assert(DirectiveCompletion.sortCompletions("org.typelevel::", names) == names)
    }

    test("code") {
      assert(find("val n = Lis|").isEmpty)
      assert(find("//> using dep org.typelevel::cats-core:2.12.0\nval n = Lis|").isEmpty)
    }
  }
}
