package almond.display

import utest._

import scala.util.Try

object FileLinkTests extends TestSuite {

  private def html(link: FileLink): String =
    link.data()(Html.mimeType)

  val tests = Tests {

    test("relative link") {
      val res = html(FileLink("data/out.csv"))
      assert(res == """<a href="data/out.csv" target='_blank'>data/out.csv</a><br>""")
    }

    test("http and https links") {
      for (scheme <- Seq("http", "https", "HTTPS")) {
        val link         = s"$scheme://example.com/a?b=1&c=2"
        val res          = html(FileLink(link))
        val expectedHref = link.replace("&", "&amp;")
        assert(res.startsWith(s"""<a href="$expectedHref" """))
      }
    }

    test("url prefix") {
      val res = html(FileLink("out.csv").withUrlPrefix("https://example.com/files/"))
      assert(res.startsWith("""<a href="https://example.com/files/out.csv" """))
    }

    test("reject other schemes") {
      val links = Seq(
        FileLink("javascript:alert(1)"),
        FileLink("JavaScript:alert(1)"),
        FileLink("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="),
        FileLink("alert(1)").withUrlPrefix("javascript:"),
        FileLink("vbscript:msgbox(1)")
      )
      for (link <- links) {
        val res = Try(html(link))
        assert(res.failed.toOption.exists { ex =>
          ex.isInstanceOf[IllegalArgumentException] && ex.getMessage.contains("Unsupported URL")
        })
      }
    }
  }
}
