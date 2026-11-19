package almond.display

import utest._

object DisplayToStringTests extends TestSuite {

  val tests = Tests {

    test("text displays") {
      assert(Html("<b>foo</b>").toString == "Html(\"<b>foo</b>\")")
      assert(Markdown("# a\n\"b\"").toString == "Markdown(\"# a\\n\\\"b\\\"\")")
      assert(Latex("\\alpha").toString == "Latex(\"\\\\alpha\")")
      assert(
        Svg.from("https://example.com/a.svg").toString ==
          "Svg.from(\"https://example.com/a.svg\")"
      )
    }

    test("progress bar") {
      assert(ProgressBar(10).toString == "ProgressBar(10)")
      assert(
        ProgressBar(2, 10).withLabel("{progress} / {total}").toString ==
          "ProgressBar(2, 10).withLabel(\"{progress} / {total}\")"
      )
    }

    test("others") {
      assert(Image(Array[Byte](1, 2, 3)).toString == "Image(3 bytes)")
      assert(
        Image(Array[Byte](1)).withFormat(Image.PNG).toString ==
          "Image(1 byte).withFormat(Image.PNG)"
      )
      assert(IFrame("https://example.com").toString == "IFrame(\"https://example.com\")")
      assert(FileLink("a.csv").toString == "FileLink(\"a.csv\")")
      assert(
        Data("text/plain" -> "a", "text/html" -> "<b>a</b>").toString ==
          "Data(text/html, text/plain)"
      )
    }
  }

}
