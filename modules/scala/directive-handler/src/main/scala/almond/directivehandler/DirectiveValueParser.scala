// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveValueParser.scala

package almond.directivehandler

import almond.directivehandler.parser.DirectiveValue

abstract class DirectiveValueParser[+T] {

  def parse(scopedDirective: ScopedDirective): Either[DirectiveException, T] =
    parse(
      scopedDirective.directive.values,
      scopedDirective.cwd,
      scopedDirective.maybePath
    )

  def parse(
    values: Seq[DirectiveValue],
    scopePath: ScopePath,
    path: Either[String, os.Path]
  ): Either[DirectiveException, T]

  final def map[U](f: T => U): DirectiveValueParser[U] =
    new DirectiveValueParser.Mapped[T, U](this, f)
}

object DirectiveValueParser {

  private final class Mapped[T, +U](underlying: DirectiveValueParser[T], f: T => U)
      extends DirectiveValueParser[U] {
    override def parse(scopedDirective: ScopedDirective): Either[DirectiveException, U] =
      underlying.parse(scopedDirective).map(f)
    def parse(
      values: Seq[DirectiveValue],
      scopePath: ScopePath,
      path: Either[String, os.Path]
    ): Either[DirectiveException, U] =
      underlying.parse(values, scopePath, path).map(f)
  }

  abstract class DirectiveSingleValueParser[+T] extends DirectiveValueParser[T] {
    def parseValue(
      value: DirectiveValue,
      cwd: ScopePath,
      path: Either[String, os.Path]
    ): Either[DirectiveException, T]

    final def parse(
      values: Seq[DirectiveValue],
      scopePath: ScopePath,
      path: Either[String, os.Path]
    ): Either[DirectiveException, T] =
      values.filter(!_.isEmpty) match {
        case Seq(value) => parseValue(value, scopePath, path)
        case _ =>
          Left(
            new UsingDirectiveValueNumError(
              path,
              "",
              "1",
              values.length
            )
          )
      }
  }

  implicit lazy val unit: DirectiveValueParser[Unit] = { (values, scopePath, path) =>
    values match {
      case Seq() => Right(())
      case Seq(value, _*) =>
        val pos = value.position(path)
        Left(new MalformedDirectiveError("Expected no value in directive", Seq(pos)))
    }
  }

  implicit class DirectiveValueParserValueOps(private val value: DirectiveValue) extends AnyVal {

    def isEmpty: Boolean =
      value match {
        case _: DirectiveValue.EmptyVal => true
        case _                          => false
      }

    def isString: Boolean =
      value match {
        case _: DirectiveValue.StringVal => true
        case _                           => false
      }
    def asString: Option[String] =
      value match {
        case s: DirectiveValue.StringVal => Some(s.value)
        case _                           => None
      }
    def isBoolean: Boolean =
      value match {
        case _: DirectiveValue.BoolVal => true
        case _                         => false
      }
    def asBoolean: Option[Boolean] =
      value match {
        case s: DirectiveValue.BoolVal => Some(s.value)
        case _                         => None
      }

    def position(path: Either[String, os.Path]): Position =
      DirectiveUtil.position(value, path, skipQuotes = value.isQuotedString)
  }

  implicit lazy val boolean: DirectiveValueParser[Boolean] = { (values, scopePath, path) =>
    values.filter(!_.isEmpty) match {
      case Seq() => Right(true)
      case Seq(v) =>
        v.asBoolean.toRight {
          new UsingDirectiveWrongValueTypeError(
            path,
            "",
            Seq("boolean"),
            ""
          )
        }
      case values0 =>
        Left(
          new MalformedDirectiveError(
            s"Unexpected values ${values0.map(_.rawText).mkString(", ")}",
            values0.map(_.position(path))
          )
        )
    }
  }

  implicit lazy val string: DirectiveSingleValueParser[String] =
    (value, scopePath, path) =>
      value.asString.toRight {
        val pos = value.position(path)
        new MalformedDirectiveError(
          s"Expected a string, got '${value.rawText}'",
          Seq(pos)
        )
      }.map(DirectiveSpecialSyntax.handlingSpecialPathSyntax(_, path))

  final case class MaybeNumericalString(value: String)

  implicit lazy val maybeNumericalString: DirectiveSingleValueParser[MaybeNumericalString] =
    (value, scopePath, path) =>
      value.asString.map(MaybeNumericalString(_)).toRight {
        val pos = value.position(path)
        new MalformedDirectiveError(
          s"Expected a string value, got '${value.rawText}'",
          Seq(pos)
        )
      }

  final case class WithScopePath[+T](scopePath: ScopePath, value: T)

  object WithScopePath {
    def empty[T](value: T): WithScopePath[T] =
      WithScopePath(ScopePath(Left("invalid"), os.sub), value)
  }

  implicit def withScopePath[T](implicit
    underlying: DirectiveValueParser[T]
  ): DirectiveValueParser[WithScopePath[T]] = {
    (values, scopePath, path) =>
      underlying.parse(values, scopePath, path)
        .map(WithScopePath(scopePath, _))
  }
  implicit def positioned[T](implicit
    underlying: DirectiveSingleValueParser[T]
  ): DirectiveSingleValueParser[Positioned[T]] = {
    (value, scopePath, path) =>
      underlying.parseValue(value, scopePath, path)
        .map(Positioned(value.position(path), _))
  }
  implicit def option[T](implicit
    underlying: DirectiveValueParser[T]
  ): DirectiveValueParser[Option[T]] =
    underlying.map(Some(_))
  implicit def list[T](implicit
    underlying: DirectiveSingleValueParser[T]
  ): DirectiveValueParser[List[T]] = {
    (values, scopePath, path) =>
      val res = values.filter(!_.isEmpty).map(underlying.parseValue(_, scopePath, path))
      val errors = res.collect {
        case Left(e) => e
      }
      if (errors.isEmpty) Right(res.collect { case Right(v) => v }.toList)
      else Left(CompositeDirectiveException(errors))
  }

}
