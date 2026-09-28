package almond

import almond.interpreter.api.ExecuteResult
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import utest._

object ImportFileTests extends TestSuite {

  private def newInterpreter(): ScalaInterpreter =
    new ScalaInterpreter(params = interpreterParams, logCtx = logCtx)

  private def text(res: ExecuteResult): String =
    res match {
      case s: ExecuteResult.Success =>
        s.data.detailedData.get("text/plain").flatMap(_.asString).getOrElse("")
      case e: ExecuteResult.Error =>
        s"ERROR: ${e.name}: ${e.message}\n${e.stackTrace.mkString("\n")}"
      case other =>
        other.toString
    }

  private def resultValue(res: ExecuteResult): String = {
    val text0 = text(res)
    // strip the `resN: Type = ` prefix of results
    text0.split(" = ", 2) match {
      case Array(_, value) => value
      case _               => text0
    }
  }

  private def error(res: ExecuteResult): String =
    res match {
      case e: ExecuteResult.Error => e.message
      case other                  => sys.error(s"Expected error, got $other")
    }

  // Scripts are looked up relative to the working directory, so we create them under it
  private def withScriptsDir[T](f: os.Path => T): T = {
    val dir = os.temp.dir(dir = os.pwd, prefix = "scripts")
    try f(dir)
    finally os.remove.all(dir)
  }

  val tests = Tests {

    test("import $file") {
      withScriptsDir { dir =>
        os.write(
          dir / "Foo.sc",
          """def foo() = "bar"
            |object Inner { def n = 2 }
            |""".stripMargin
        )
        val interp = newInterpreter()
        val res0   = interp.execute(s"import $$file.${dir.last}.Foo")
        assert(res0.isInstanceOf[ExecuteResult.Success])
        val res1 = interp.execute("Foo.foo()")
        assert(resultValue(res1) == "\"bar\"")
        val res2 = interp.execute("Foo.Inner.n")
        assert(resultValue(res2) == "2")
      }
    }

    test("import $file reloads modified script") {
      withScriptsDir { dir =>
        os.write(dir / "Foo.sc", "def foo() = \"bar\"\n")
        val interp = newInterpreter()
        val res0   = interp.execute(s"import $$file.${dir.last}.Foo")
        assert(res0.isInstanceOf[ExecuteResult.Success])
        val res1 = interp.execute("Foo.foo()")
        assert(resultValue(res1) == "\"bar\"")

        os.write.over(dir / "Foo.sc", "def foo() = \"baz\"\n")
        val res2 = interp.execute(s"import $$file.${dir.last}.Foo")
        assert(res2.isInstanceOf[ExecuteResult.Success])
        val res3 = interp.execute("Foo.foo()")
        assert(resultValue(res3) == "\"baz\"")

        // unchanged script: not run again
        os.write.over(dir / "Foo.sc", "def foo() = \"baz\"\n")
        val res4 = interp.execute(s"import $$file.${dir.last}.Foo")
        assert(res4.isInstanceOf[ExecuteResult.Success])
        val res5 = interp.execute("Foo.foo()")
        assert(resultValue(res5) == "\"baz\"")
      }
    }

    test("import $file runs script once") {
      withScriptsDir { dir =>
        os.write(
          dir / "Counter.sc",
          "val n = { almond.ImportFileTestsHelper.counter.incrementAndGet() }\n"
        )
        val interp = newInterpreter()
        val before = ImportFileTestsHelper.counter.get()
        interp.execute(s"import $$file.${dir.last}.Counter")
        interp.execute(s"import $$file.${dir.last}.Counter")
        val res = interp.execute("Counter.n")
        assert(resultValue(res) == (before + 1).toString)
        assert(ImportFileTestsHelper.counter.get() == before + 1)
      }
    }

    test("using script directive") {
      withScriptsDir { dir =>
        os.write(
          dir / "Foo.sc",
          """def foo() = "bar"
            |object Inner { def n = 2 }
            |""".stripMargin
        )
        val interp = newInterpreter()
        // script usable from the cell with the directive
        val res0 = interp.execute(
          s"""//> using script ${dir.last}/Foo.sc
             |Foo.foo()
             |""".stripMargin
        )
        assert(resultValue(res0) == "\"bar\"")
        // and from the next ones
        val res1 = interp.execute("Foo.Inner.n")
        assert(resultValue(res1) == "2")
      }
    }

    test("using script directive alone in cell") {
      withScriptsDir { dir =>
        os.write(dir / "Foo.sc", "def foo() = \"bar\"\n")
        val interp = newInterpreter()
        val res0   = interp.execute(s"//> using script ${dir.last}/Foo.sc")
        assert(res0.isInstanceOf[ExecuteResult.Success])
        val res1 = interp.execute("Foo.foo()")
        assert(resultValue(res1) == "\"bar\"")
      }
    }

    test("using script directive with absolute path and non-identifier name") {
      withScriptsDir { dir =>
        os.write(dir / "my-script.sc", "def foo() = \"bar\"\n")
        val interp = newInterpreter()
        val res0   = interp.execute(s"//> using script ${dir / "my-script.sc"}")
        assert(res0.isInstanceOf[ExecuteResult.Success])
        val res1 = interp.execute("`my-script`.foo()")
        assert(resultValue(res1) == "\"bar\"")
      }
    }

    test("using scripts directive with several scripts") {
      withScriptsDir { dir =>
        os.makeDir.all(dir / "sub")
        os.write(dir / "Foo.sc", "def foo() = \"foo\"\n")
        os.write(dir / "sub" / "Bar.sc", "def bar() = \"bar\"\n")
        val interp = newInterpreter()
        val res0 = interp.execute(
          s"""//> using scripts ${dir.last}/Foo.sc ${dir.last}/sub/Bar
             |Foo.foo() + Bar.bar()
             |""".stripMargin
        )
        assert(resultValue(res0) == "\"foobar\"")
      }
    }

    test("using script directive missing script") {
      withScriptsDir { dir =>
        val interp = newInterpreter()
        val res0   = interp.execute(s"//> using script ${dir.last}/Nope.sc")
        val msg    = error(res0)
        assert(msg.contains("Script not found"))
        assert(msg.contains("Nope.sc"))
      }
    }

    test("using script directive script throwing") {
      withScriptsDir { dir =>
        os.write(dir / "Throws.sc", "val n: Int = sys.error(\"nope\")\n")
        val interp = newInterpreter()
        val res0   = interp.execute(s"//> using script ${dir.last}/Throws.sc")
        assert(res0.isInstanceOf[ExecuteResult.Error])
        val err = res0.asInstanceOf[ExecuteResult.Error]
        assert(err.name.contains("RuntimeException"))
        assert(err.message.contains("nope"))
      }
    }

    test("using script directive reloads modified script") {
      withScriptsDir { dir =>
        os.write(dir / "Foo.sc", "def foo() = \"bar\"\n")
        val interp = newInterpreter()
        val res0 = interp.execute(
          s"""//> using script ${dir.last}/Foo.sc
             |Foo.foo()
             |""".stripMargin
        )
        assert(resultValue(res0) == "\"bar\"")
        os.write.over(dir / "Foo.sc", "def foo() = \"baz\"\n")
        val res1 = interp.execute(
          s"""//> using script ${dir.last}/Foo.sc
             |Foo.foo()
             |""".stripMargin
        )
        assert(resultValue(res1) == "\"baz\"")
      }
    }

    test("script importing another script") {
      withScriptsDir { dir =>
        os.write(dir / "Foo.sc", "def foo() = \"foo\"\n")
        os.write(dir / "Bar.sc", "import $file.Foo\ndef bar() = Foo.foo() + \"bar\"\n")
        val interp = newInterpreter()
        val res0 = interp.execute(
          s"""//> using script ${dir.last}/Bar.sc
             |Bar.bar()
             |""".stripMargin
        )
        assert(resultValue(res0) == "\"foobar\"")
      }
    }
  }
}

object ImportFileTestsHelper {
  val counter = new java.util.concurrent.atomic.AtomicInteger
}
