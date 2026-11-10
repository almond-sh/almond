// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/test/scala/scala/cli/directivehandler/tests/DirectiveTestsDefinitions.scala

package almond.directivehandler.tests

import almond.directivehandler._

object DirectiveTestsDefinitions {

  @DirectiveUsage("//> thing", "//> thing but markdown")
  @DirectiveDescription("desc", "markdown desc")
  @DirectiveExamples("//> using prefixx.thing a")
  @DirectiveExamples("//> using prefixx.thing b")
  @DirectiveExamples("//> using prefixx.thing c")
  @DirectiveExamples("//> using prefixx.thing a b c")
  @DirectiveGroupName("the name")
  @DirectiveTag("tag1")
  @DirectiveTag("tag2")
  @DirectivePrefix("prefixx.")
  case class Directives(
    thing: List[String] = Nil,
    @DirectiveName("other")
    enableTheOtherThing: Option[Boolean] = None
  )

}
