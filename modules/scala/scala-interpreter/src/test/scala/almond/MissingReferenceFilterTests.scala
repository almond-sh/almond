package almond

import java.io.PrintStream
import java.nio.charset.StandardCharsets

import almond.internals.MissingReferenceFilter
import utest._

object MissingReferenceFilterTests extends TestSuite {

  private val esc                = 27.toChar.toString
  private def yellow(s: String)  = s"$esc[33m$s$esc[0m"
  private def magenta(s: String) = s"$esc[35m$s$esc[0m"
  private val fin                = yellow("final")
  private val lzy                = yellow("lazy")
  private val mod                = yellow("module")
  private val shapeless          = s"${yellow("package")} ${magenta("shapeless")}"

  // the line separators of the multi-line literals below depend on the checkout (CRLF on Windows)
  private def text(s: String): String = s.stripMargin.replace("\r\n", "\n")

  private def filtered(input: String): String = {
    val b      = new StringBuilder
    val filter = new MissingReferenceFilter(b.append(_))
    val stream = filter.outputStream()
    val ps     = new PrintStream(stream, true, StandardCharsets.UTF_8.name())
    ps.print(input)
    ps.flush()
    stream.flushPartialLine()
    b.result()
  }

  val tests = Tests {

    test("compiler output") {
      // What the Scala 3.9.0 compiler prints with plotly-almond_2.13 on the class path
      val input =
        text(s"""before
           |***** missing reference, looking for HNil/T in $shapeless
           |decls = Scope{}
           |***** missing reference, looking for :+:/T in $shapeless
           |decls = Scope{
           |  $fin $lzy $mod object Generic: shapeless.Generic
           |  $fin $mod object Generic extends  {this: shapeless.Generic.type =>} at
           |    (shapeless : shapeless.type)
           |}
           |***** missing reference, looking for DecodeJson/T in package argonaut
           |decls = Scope{}
           |**** slow search found: val DecodeJson
           |after
           |""")
      val expected =
        text("""before
          |after
          |""")
      val res = filtered(input)
      assert(res == expected)
    }

    test("other lines are kept") {
      val input =
        text("""a
          |decls = Scope{}
          |}
          |  b
          |**** slow search found: c
          |d""")
      val res = filtered(input)
      assert(res == input)
    }

    test("line after an unexpected one") {
      // a "missing reference" line not followed by decls: the next line is kept
      val input =
        text("""***** missing reference, looking for A/T in package a
          |b
          |""")
      val res = filtered(input)
      assert(res == "b\n")
    }

    test("line after the decls") {
      val input =
        text("""***** missing reference, looking for A/T in package a
          |decls = Scope{
          |  val a: Int
          |}
          |b
          |""")
      val res = filtered(input)
      assert(res == "b\n")
    }
  }
}
