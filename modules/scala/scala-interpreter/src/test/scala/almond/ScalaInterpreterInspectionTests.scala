package almond

import almond.interpreter.Interpreter
import almond.protocol.Codecs.stringCodec
import almond.testkit.TestLogging.logCtx
import almond.TestUtil._
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import utest._

/** Inspections specific to Scala 3 (see also the "inspection results" tests of
  * ScalaInterpreterTests, shared with Scala 2)
  */
object ScalaInterpreterInspectionTests extends TestSuite {

  private def newInterpreter(): Interpreter =
    new ScalaInterpreter(params = interpreterParams, logCtx = logCtx)

  // Scala 3 inspections are only made of text/plain, with ANSI colors, that we remove here
  private def lines(interpreter: Interpreter, code: String, pos: Int): Vector[String] =
    interpreter.inspect(code, pos, detailLevel = 1)
      .flatMap(_.data.get("text/plain"))
      .map(raw => readFromArray(raw.value)(stringCodec))
      .map(_.replaceAll("\u001b\\[[0-9;]*m", "").linesIterator.toVector)
      .getOrElse(sys.error(s"No text inspection result for '$code' at $pos"))

  // The docs of Java libraries and of Scala 2 libraries are read from source JARs
  // (from the src.zip of the JDK for the JDK classes), see SourceDocs
  private def hasJdkSources =
    java.nio.file.Files.isRegularFile(
      java.nio.file.Paths.get(sys.props("java.home"), "lib", "src.zip")
    )

  val tests = Tests {
    test("scaladoc") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()

        val localCode =
          """/** Adds one to `n`.
            |  *
            |  * @param n the number
            |  *   to increment
            |  */
            |def inc(n: Int): Int = n + 1""".stripMargin
        val localRes = lines(interpreter, localCode, localCode.indexOf("inc(") + 1)
        val expectedLocalRes = Vector(
          "Signature: inc(n: Int): Int",
          "Docstring:",
          "Adds one to `n`.",
          "",
          "Parameters:",
          "  n: the number to increment"
        )
        assert(localRes == expectedLocalRes)

        // read from the TASTy files of the Scala 3 library
        val libCode = "scala.util.boundary"
        val libRes  = lines(interpreter, libCode, libCode.indexOf("boundary") + 1)
        assert(libRes(0).matches("Type: +scala.util.boundary"))
        assert(libRes(1) == "Docstring:")
        assert(libRes(2) == "A boundary that can be exited by `break` calls.")
        assert(libRes.last.matches("Defined in: +scala.util"))

        val methodCode = "scala.compiletime.summonInline[Int]"
        val methodRes  = lines(interpreter, methodCode, methodCode.indexOf("summonInline") + 1)
        assert(methodRes(0).matches("Signature: +summonInline\\[T\\]: T"))
        assert(methodRes.contains("Type Parameters:"))
        assert(methodRes.contains("  T: the type of the value to be summoned"))
        assert(methodRes.last.matches("Defined in: +scala.compiletime.*"))
      }
    }

    test("incomplete code") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()
        val code        = "List(1, 2).map("
        // front-ends send the position of the cursor, right after the parenthesis here
        val res = lines(interpreter, code, code.length)
        assert(res.head.matches("Signature: +map\\[B\\]\\(f: Int => B\\): List\\[B\\]"))
        // scaladoc variables, like $coll, should have been substituted
        assert(!res.exists(_.contains("$coll")))
      }
    }

    test("java docs") {
      if (TestUtil.isScala2 || !hasJdkSources) "disabled"
      else {
        val interpreter = newInterpreter()
        val code        = "\"abc\".substring("
        val res         = lines(interpreter, code, code.length)
        // parameter names come from the sources too
        assert(res(0).matches("Signature: +substring\\(beginIndex: Int\\): String"))
        assert(res(1) == "Docstring:")
        assert(res(2).startsWith("Returns a string that is a substring of this string."))
        assert(res.contains("  beginIndex: the beginning index, inclusive."))
        assert(res.last.matches("Defined in: +java.lang.String"))
      }
    }

    test("overloads") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()
        // no alternative of BigDecimal.apply can be picked with no argument
        val code = "BigDecimal("
        val res  = lines(interpreter, code, code.length)
        assert(res(0) == "Signature:")
        // long alternatives span several lines
        val signatures = res.drop(1).takeWhile(_ != "Docstring:").filter(_.startsWith("apply("))
        assert(signatures.length > 3)
        assert(signatures.exists(_.startsWith("apply(i: Int")))
      }
    }

    test("java overloads") {
      if (TestUtil.isScala2 || !hasJdkSources) "disabled"
      else {
        val interpreter = newInterpreter()
        val code        = "Math.max("
        val res         = lines(interpreter, code, code.length)
        assert(res(0) == "Signature:")
        val signatures = res.drop(1).takeWhile(_ != "Docstring:").toSet
        assert(signatures == Set(
          "max(a: Int, b: Int): Int",
          "max(a: Long, b: Long): Long",
          "max(a: Float, b: Float): Float",
          "max(a: Double, b: Double): Double"
        ))
        assert(res.last.matches("Defined in: +java.lang.Math"))
      }
    }

    test("scala 2 library docs") {
      // before Scala 3.8, the standard library is compiled by Scala 2 and its docs are read
      // from its source JAR, since 3.8, they're read from its TASTy files
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()
        val code        = "List(1, 2).map("
        val res         = lines(interpreter, code, code.length)
        assert(res.exists(_.startsWith("Builds a new list")))
        assert(res.contains("  f: the function to apply to each element."))

        val inheritedCode = "List(1, 2).isEmpty"
        val inheritedRes  = lines(interpreter, inheritedCode, inheritedCode.indexOf("isEmpty") + 1)
        assert(inheritedRes.exists(_.startsWith("Tests whether the list is empty.")))
        // same indentation for the lines of the main doc, including the inherited ones
        assert(!inheritedRes.exists(_.startsWith(" Note")))
      }
    }

    test("derives clause") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()
        val code        = "case class P(x: Int) derives CanEqual"
        val res         = lines(interpreter, code, code.indexOf("CanEqual") + 2)
        assert(res.head.matches("Type: +CanEqual\\[P, P\\]"))
        assert(res.last.matches("Defined in: +scala"))

        // qualified type classes, inspected from any of their parts
        assert(interpreter.execute(
          """object Outer {
            |  trait Inner[T]
            |  object Inner { def derived[T]: Inner[T] = new Inner[T] {} }
            |}""".stripMargin
        ).success)
        val qualifiedCode = "case class R(x: Int) derives Outer.Inner"
        for (part <- Seq("Outer", "Inner")) {
          val qualifiedRes = lines(interpreter, qualifiedCode, qualifiedCode.indexOf(part) + 1)
          assert(qualifiedRes.head.matches("Type: +Outer\\.Inner\\[R\\]"))
        }
      }
    }

    test("types defined in cells") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()
        assert(interpreter.execute("case class Q(x: Int)").success)
        val code = "def make(n: Int): Q = Q(n)"
        val res  = lines(interpreter, code, code.indexOf("make") + 1)
        // no wrapper prefixes, like ammonite.$sess.cmd0.Helper.Q
        assert(res == Vector("Signature: make(n: Int): Q"))
      }
    }

    test("default arguments") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()

        // from the code of the cell
        val code =
          "def greet(name: String, greeting: String = \"Hello\", times: Int = 1): String = name"
        val res = lines(interpreter, code, code.indexOf("greet") + 1)
        assert(res == Vector(
          "Signature: greet(name: String, greeting: String = \"Hello\", times: Int = 1): String"
        ))

        // from a source JAR
        val libCode = "List(1, 2).startsWith("
        val libRes  = lines(interpreter, libCode, libCode.length)
        assert(res.nonEmpty && libRes.head.contains("offset: Int = 0"))
      }
    }

    test("long signatures") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()
        val code =
          "def configure(host: String, port: Int = 8080, secure: Boolean = false, " +
            "timeoutSeconds: Long = 30L, retries: Int = 3): Unit = ()"
        val res = lines(interpreter, code, code.indexOf("configure") + 1)
        // one parameter per line
        assert(res == Vector(
          "Signature:",
          "configure(",
          "  host: String,",
          "  port: Int = 8080,",
          "  secure: Boolean = false,",
          "  timeoutSeconds: Long = 30L,",
          "  retries: Int = 3",
          "): Unit"
        ))
      }
    }

    test("literal arguments") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()

        // like IPython, the method the literal is passed to
        val code = "List(1, 2).startsWith(List(1), 1)"
        val res  = lines(interpreter, code, code.lastIndexOf("1") + 1)
        assert(res.head.matches("Signature: +startsWith\\[B >: Int\\]\\(.*"))

        // no docs of an enclosing definition for a literal outside of calls
        val valCode = "val s = \"abc\""
        val valRes  = lines(interpreter, valCode, valCode.indexOf("abc") + 1)
        assert(valRes == Vector("Type: String"))
      }
    }

    test("implicit apply calls") {
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()
        assert(
          interpreter.execute("object greeter { def apply(name: String): String = name }").success
        )
        val code = "greeter("
        val res  = lines(interpreter, code, code.length)
        // the name of the method that's actually called, where it's defined telling which
        // apply that is
        assert(res.head.matches("Signature: +apply\\(name: String\\): String"))
        assert(res.last.matches("Defined in: +greeter"))
      }
    }

    test("no result outside of trees") {
      // the Scala 2 implementation returns the type of the enclosing tree instead
      if (TestUtil.isScala2) "disabled"
      else {
        val interpreter = newInterpreter()
        val code        = "val a = 1   "
        assert(interpreter.inspect(code, code.length - 1, detailLevel = 1).isEmpty)
      }
    }
  }
}
