package almond.debugger

import utest._

object CellFrameNameTests extends TestSuite {

  // frame names from the debug adapter, and how they should be shown
  val expected = Seq(
    // Scala 3, decoded by the debug adapter
    "cmd2.Helper.<anon fun>(i: Int): Unit" -> "<anon fun>(i: Int): Unit",
    "cmd2.Helper.f(x: Int): Int"           -> "f(x: Int): Int",
    "cmd2.Helper.<init>(): Unit"           -> "cmd2",
    // Scala 2, JVM names
    "cmd2$Helper.f(int): int"                        -> "f(int): int",
    "cmd2$Helper.$anonfun$r$1(cmd2$Helper,int): int" -> "<anon fun>(int): int",
    "cmd2$Helper.$anonfun$new$1(int): void"          -> "<anon fun>(int): void",
    "cmd2$Helper.<init>(cmd2): void"                 -> "cmd2",
    // not from that cell
    "cmd12$Helper.f(int): int"       -> "cmd12$Helper.f(int): int",
    "Range.foreach(Function1): void" -> "Range.foreach(Function1): void"
  )

  val tests = Tests {
    test("cell frame names") {
      for ((name, expected0) <- expected) {
        val obtained = KernelDebugger.cellFrameName(name, "cmd2")
        assert(obtained == expected0)
      }
    }
  }
}

object JarSourceTests extends TestSuite {
  val tests = Tests {
    test("jar sources") {
      val uri =
        "jar:file:///home/user/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/" +
          "scala-library/3.10.1/scala-library-3.10.1-sources.jar!/scala/collection/immutable/Range.scala"
      assert(
        KernelDebugger.jarSource(uri) ==
          Some(("scala-library-3.10.1-sources.jar", "scala/collection/immutable/Range.scala"))
      )
      assert(
        KernelDebugger.jarSource("jar:file:///a/b/debug-core-0.34.0%2B44-sources.jar!/a/B.java") ==
          Some(("debug-core-0.34.0+44-sources.jar", "a/B.java"))
      )
      assert(KernelDebugger.jarSource("/tmp/foo/Bar.scala").isEmpty)
    }
  }
}

object DisplayVariableTests extends TestSuite {

  // values and types of variables from the debug adapter, and the value JupyterLab should show
  val expected = Seq(
    ("3", "int")                                         -> "3",
    ("2.5", "double")                                    -> "2.5",
    ("true", "boolean")                                  -> "true",
    ("c", "char")                                        -> "'c'",
    ("\"Ada Lovelace\"", "String")                       -> "\"Ada Lovelace\"",
    ("\"a@1 \"b\"\"", "String")                          -> "\"a@1 \"b\"\"",
    ("String[2]@9283", "String[]")                       -> "String[2]",
    ("int[0]@12", "int[]")                               -> "int[0]",
    ("cmd2$Helper$P@9285 \"P(1,2.5)\"", "cmd2$Helper$P") -> "P(1,2.5)",
    ("$colon$colon@9284 \"List(1, 2)\"", "$colon$colon") -> "List(1, 2)",
    ("Foo@9286 \"a\nb\"", "Foo")                         -> "a\nb",
    ("Foo@9287", "Foo")                                  -> "Foo@9287",
    ("null", "String")                                   -> "null"
  )

  val tests = Tests {
    test("display variables") {
      for (((value, tpe), expected0) <- expected) {
        val variable = ujson.Obj(
          "name"               -> "x",
          "value"              -> value,
          "type"               -> tpe,
          "variablesReference" -> 0
        )
        val obtained = KernelDebugger.displayVariable(variable)
        assert(obtained("value").str == expected0)
        assert(!obtained.value.contains("type"))
        assert(obtained("name").str == "x")
      }
    }
  }
}
