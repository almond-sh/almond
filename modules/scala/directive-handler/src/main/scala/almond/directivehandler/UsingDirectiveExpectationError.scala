// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/UsingDirectiveExpectationError.scala

package almond.directivehandler

sealed abstract class UsingDirectiveExpectationError(
  message: String
) extends DirectiveException(message)

final class UsingDirectiveWrongValueTypeError(
  maybePath: Either[String, os.Path],
  key: String,
  expectedTypes: Seq[String],
  hint: String = ""
) extends UsingDirectiveExpectationError(
      s"""${expectedTypes.mkString(
          ", or "
        )} expected for the $key using directive key${maybePath.map(path => s" at $path").getOrElse(
          ""
        )}.
         |$hint""".stripMargin
    )

final class UsingDirectiveValueNumError(
  maybePath: Either[String, os.Path],
  key: String,
  expectedBounds: String,
  providedValueNum: Int
) extends UsingDirectiveExpectationError(
      s"expected $expectedBounds for the $key using directive key${maybePath.map(path =>
          s" at $path"
        ).getOrElse(
          ""
        )}; but got $providedValueNum values, instead."
    ) {}
