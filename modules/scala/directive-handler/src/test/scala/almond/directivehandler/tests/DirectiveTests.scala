// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/test/scala/scala/cli/directivehandler/tests/DirectiveTests.scala

package almond.directivehandler.tests

import com.eed3si9n.expecty.Expecty.expect
import org.junit.Test

import almond.directivehandler._
import almond.directivehandler.EitherSequence._

class DirectiveTests {

  import DirectiveTestsDefinitions._

  @Test def simple(): Unit = {

    val handler: DirectiveHandler[Directives] = DirectiveHandler.deriver[Directives].derive

    expect(handler.usage == "//> thing")
    expect(handler.usageMd == "//> thing but markdown")
    expect(handler.description == "desc")
    expect(handler.descriptionMd == "markdown desc")

    if (DirectiveTestsScalaVersionUtil.isScala2)
      expect(handler.examples.headOption == Some("//> using prefixx.thing a"))
    else
      expect(handler.examples == Seq("a", "b", "c", "a b c").map("//> using prefixx.thing " + _))

    expect(handler.keys == Seq(
      "prefixx.thing",
      "prefixx.enableTheOtherThing",
      "prefixx.enable-the-other-thing",
      "prefixx.other"
    ))
    expect(handler.name == "the name")

    if (DirectiveTestsScalaVersionUtil.isScala2)
      expect(handler.tags.headOption == Some("tag1"))
    else
      expect(handler.tags == Seq("tag1", "tag2"))

    val input =
      """//> using prefixx.thing foo aa bb
        |//> using prefixx.other
        |""".stripMargin

    val path      = Left("script.sc")
    val scopePath = ScopePath(Left("."), os.sub)

    val expectedRes = Seq(
      ProcessedDirective(Some(Directives(thing = List("foo", "aa", "bb"))), Nil),
      ProcessedDirective(Some(Directives(enableTheOtherThing = Some(true))), Nil)
    )

    val res = handler.parse(input, path, scopePath)
      .fold(e => throw new Exception(e), identity)

    if (res != expectedRes) {
      pprint.err.log(res)
      pprint.err.log(expectedRes)
    }
    expect(res == expectedRes)
  }

}
