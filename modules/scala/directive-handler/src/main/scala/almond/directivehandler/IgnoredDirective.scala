// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/IgnoredDirective.scala

package almond.directivehandler

import almond.directivehandler.parser.DirectiveValue

final case class IgnoredDirective(directive: ScopedDirective)

object IgnoredDirective {

  implicit val valueParser: DirectiveValueParser[IgnoredDirective] =
    new DirectiveValueParser[IgnoredDirective] {
      override def parse(scopedDirective: ScopedDirective)
        : Either[DirectiveException, IgnoredDirective] =
        Right(IgnoredDirective(scopedDirective))
      def parse(
        values: Seq[DirectiveValue],
        scopePath: ScopePath,
        path: Either[String, os.Path]
      ): Either[DirectiveException, IgnoredDirective] =
        Right(
          IgnoredDirective(
            ScopedDirective(
              StrictDirective("", values),
              path,
              scopePath
            )
          )
        )
    }

}
