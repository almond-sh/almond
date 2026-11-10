// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveHandler.scala

package almond.directivehandler

import almond.directivehandler.EitherSequence._

trait DirectiveHandler[+T] { self =>
  def name: String
  def description: String
  def descriptionMd: String = description
  def usage: String
  def usageMd: String       = s"`$usage`"
  def examples: Seq[String] = Nil
  def tags: Seq[String]     = Nil

  def keys: Seq[String]

  def handleValues(
    scopedDirective: ScopedDirective
  ): Either[DirectiveException, ProcessedDirective[T]]

  final def parse(
    input: String,
    path: Either[String, os.Path],
    scopePath: ScopePath
  ): Either[DirectiveException, Seq[ProcessedDirective[T]]] =
    ExtractedDirectives.from(input: IndexedSeq[Char], path).flatMap { extracted =>
      extracted
        .directives
        .map(dir => ScopedDirective(dir, path, scopePath))
        .map(handleValues)
        .sequence
        .left.map(CompositeDirectiveException(_))
    }

  def map[U](f: T => U): DirectiveHandler[U] =
    new DirectiveHandler[U] {
      def name                   = self.name
      def usage                  = self.usage
      override def usageMd       = self.usageMd
      def description            = self.description
      override def descriptionMd = self.descriptionMd
      override def examples      = self.examples
      override def tags          = self.tags

      def keys = self.keys

      def handleValues(scopedDirective: ScopedDirective) =
        self.handleValues(scopedDirective)
          .map(_.map(f))
    }

  def ignore: DirectiveHandler[IgnoredDirective] =
    new DirectiveHandler[IgnoredDirective] {
      def name: String                   = self.name
      def description: String            = "[ignored]"
      override def descriptionMd: String = "ignored"
      def usage: String                  = ""
      override def usageMd: String       = ""
      override def examples: Seq[String] = Nil
      override def tags: Seq[String]     = Nil

      def keys: Seq[String] = self.keys

      private lazy val fieldNames = keys.toSet

      def handleValues(
        scopedDirective: ScopedDirective
      ): Either[DirectiveException, ProcessedDirective[IgnoredDirective]] =
        Right(ProcessedDirective(Some(IgnoredDirective(scopedDirective)), Nil))
    }

}

object DirectiveHandler extends DirectiveHandlerMacros {

  // from https://github.com/alexarchambault/case-app/blob/7ac9ae7cc6765df48eab27c4e35c66b00e4469a7/core/shared/src/main/scala/caseapp/core/util/CaseUtil.scala#L5-L22
  def pascalCaseSplit(s: List[Char]): List[String] =
    if (s.isEmpty)
      Nil
    else if (!s.head.isUpper) {
      val (w, tail) = s.span(!_.isUpper)
      w.mkString :: pascalCaseSplit(tail)
    }
    else if (s.tail.headOption.forall(!_.isUpper)) {
      val (w, tail) = s.tail.span(!_.isUpper)
      (s.head :: w).mkString :: pascalCaseSplit(tail)
    }
    else {
      val (w, tail) = s.span(_.isUpper)
      if (tail.isEmpty)
        w.mkString :: pascalCaseSplit(tail)
      else
        w.init.mkString :: pascalCaseSplit(w.last :: tail)
    }

  def normalizeName(s: String): String = {
    val elems = s.split('-')
    (elems.head +: elems.tail.map(_.capitalize)).mkString
  }

}
