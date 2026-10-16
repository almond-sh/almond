package almond.display

import utest._

object HtmlEscapingTests extends TestSuite {

  private def html(d: Display): String =
    d.data()(Html.mimeType)

  val tests = Tests {

    test("escape") {
      val res      = HtmlUtils.escape("""<a href="x">'&'</a> é""")
      val expected = "&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt; é"
      assert(res == expected)
    }

    test("allowed URLs") {
      val allowed = Seq(
        "https://example.com/a",
        "HTTP://example.com/a",
        "//example.com/a",
        "/files/a.csv",
        "files/a.csv",
        "files/a:b.csv",
        "?a=b:c",
        "#a:b",
        ""
      )
      val disallowed = Seq(
        "javascript:alert(1)",
        "JavaScript:alert(1)",
        " javascript:alert(1)",
        "\u0001javascript:alert(1)",
        "java\tscript:alert(1)",
        "java\nscript:alert(1)",
        "data:text/html,<script>alert(1)</script>",
        "vbscript:msgbox(1)",
        "file:///etc/passwd"
      )
      for (url <- allowed)
        assert(HtmlUtils.isAllowedUrl(url))
      for (url <- disallowed)
        assert(!HtmlUtils.isAllowedUrl(url))
    }

    test("Image") {
      val res = html(
        Image.from("https://x/a'onerror='alert(1)").withWidth(Some("1 onerror=alert(1)"))
      )
      assert(!res.contains("'onerror"))
      assert(res.contains("src='https://x/a&#39;onerror=&#39;alert(1)'"))
      assert(res.contains("width=\"1 onerror=alert(1)\""))
    }

    test("IFrame") {
      test("escaping") {
        val res = html(
          IFrame("https://x/\"><script>alert(1)</script>").withWidth("1\" onload=\"alert(1)")
        )
        assert(!res.contains("<script>"))
        assert(!res.contains("\" onload"))
        assert(res.contains("src=\"https://x/&quot;&gt;&lt;script&gt;"))
      }
      test("javascript URL") {
        val ex = assertThrows[IllegalArgumentException] {
          html(IFrame("javascript:alert(document.domain)"))
        }
        assert(ex.getMessage.contains("javascript:"))
      }
      test("relative URL") {
        val res = html(IFrame("files/page.html"))
        assert(res.contains("src=\"files/page.html\""))
      }
    }

    test("ProgressBar") {
      val res = html(ProgressBar(1, 10).withLabel("<img src=x onerror=alert(1)> {progress}"))
      assert(!res.contains("<img"))
      assert(res.contains("&lt;img src=x onerror=alert(1)&gt; 1</label>"))
      val text = ProgressBar(1, 10).withLabel("<b>").data()(Text.mimeType)
      assert(text.startsWith("<b>"))
    }

    test("FileLink") {
      test("escaping") {
        val res = html(FileLink("a&b_é.csv"))
        assert(res.contains(">a&amp;b_é.csv</a>"))
        assert(res.contains("href=\"a&amp;b_%C3%A9.csv\""))
      }
      test("javascript URL") {
        assertThrows[IllegalArgumentException] {
          html(FileLink("javascript:alert(1)"))
        }
        assertThrows[IllegalArgumentException] {
          html(FileLink("alert(1)").withUrlPrefix("javascript:"))
        }
      }
    }
  }
}
