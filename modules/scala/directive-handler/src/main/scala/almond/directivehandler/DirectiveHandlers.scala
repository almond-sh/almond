// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveHandlers.scala

package almond.directivehandler

import almond.directivehandler.EitherSequence._

final case class DirectiveHandlers[T](
  handlers: Seq[DirectiveHandler[T]],
  customHandler: String => Option[DirectiveHandler[T]] = (key: String) => None
) {

  private lazy val handlersMap = handlers.flatMap(h => h.keys.map(_ -> h)).toMap

  def parse(
    input: String,
    path: Either[String, os.Path],
    scopePath: ScopePath
  ): Either[DirectiveException, Seq[ProcessedDirective[T]]] =
    ExtractedDirectives.from(input: IndexedSeq[Char], path).flatMap { extracted =>
      extracted
        .directives
        .map(dir => ScopedDirective(dir, path, scopePath))
        .map { dir =>
          val key = dir.directive.key
          handlersMap.get(key).orElse(customHandler(key)) match {
            case Some(h) =>
              h.handleValues(dir)
            case None =>
              Left(dir.unusedDirectiveError)
          }
        }
        .sequence
        .left.map(CompositeDirectiveException(_))
    }

  def map[U](f: T => U): DirectiveHandlers[U] =
    copy(
      handlers = handlers.map(_.map(f)),
      customHandler = key => customHandler(key).map(_.map(f))
    )

  def mapDirectives[U](f: DirectiveHandler[T] => DirectiveHandler[U]): DirectiveHandlers[U] =
    copy(
      handlers = handlers.map(f),
      customHandler = key => customHandler(key).map(f)
    )

  def ++(other: DirectiveHandlers[T]): DirectiveHandlers[T] =
    DirectiveHandlers(
      handlers = handlers ++ other.handlers,
      customHandler = key => customHandler(key).orElse(other.customHandler(key))
    )

  def addCustomHandler(f: String => Option[DirectiveHandler[T]]): DirectiveHandlers[T] =
    copy(
      customHandler = key => customHandler(key).orElse(f(key))
    )

}
