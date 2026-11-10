// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveHandlerMacros.scala2.scala

package almond.directivehandler

import shapeless.{Generic, HList, HNil, Strict, Witness, ::}
import shapeless.labelled.{FieldType, field}

import java.util.Locale

import almond.directivehandler.EitherSequence._
import shapeless.AllAnnotations
import caseapp.util.AnnotationList
import shapeless.LabelledGeneric
import shapeless.Annotation
import shapeless.Annotations
import caseapp.util.AnnotationOption

object DirectiveHandlerMacros {

  sealed abstract class HListDirectiveHandlerBuilder[
    L <: HList,
    -D <: HList,
    -N <: HList
  ] {
    def actualDefault(default: D): L
    def handler(
      name: String,
      description: DirectiveDescription,
      prefix: Option[DirectivePrefix],
      usage: DirectiveUsage,
      examples: Seq[DirectiveExamples],
      tags: Seq[DirectiveTag],
      default: => D,
      names: N
    ): DirectiveHandler[L]
  }

  object HListDirectiveHandlerBuilder {
    implicit def nil: HListDirectiveHandlerBuilder[HNil, HNil, HNil] =
      new HListDirectiveHandlerBuilder[HNil, HNil, HNil] {
        def actualDefault(default: HNil) = HNil
        def handler(
          name0: String,
          description0: DirectiveDescription,
          prefix: Option[DirectivePrefix],
          usage0: DirectiveUsage,
          examples0: Seq[DirectiveExamples],
          tags0: Seq[DirectiveTag],
          default: => HNil,
          names: HNil
        ) =
          new DirectiveHandler[HNil] {
            def name                   = name0
            def description            = description0.description
            override def descriptionMd = description0.descriptionMd
            def usage                  = usage0.usage
            override def usageMd       = usage0.usageMd
            override def examples      = examples0.map(_.examples)
            override def tags          = tags0.map(_.tag)
            def keys                   = Nil
            def handleValues(scopedDirective: ScopedDirective)
              : Either[DirectiveException, ProcessedDirective[HNil]] =
              Left(new UnexpectedDirectiveError(scopedDirective.directive.key))
          }
      }

    implicit def cons[K <: Symbol, H, TL <: HList, TD <: HList, TN <: HList](implicit
      fieldName: Witness.Aux[K],
      parser: Strict[DirectiveValueParser[H]],
      tailBuilder: HListDirectiveHandlerBuilder[TL, TD, TN]
    ): HListDirectiveHandlerBuilder[
      FieldType[K, H] :: TL,
      Some[H] :: TD,
      List[DirectiveName] :: TN
    ] =
      new HListDirectiveHandlerBuilder[
        FieldType[K, H] :: TL,
        Some[H] :: TD,
        List[DirectiveName] :: TN
      ] {
        def actualDefault(default: Some[H] :: TD): FieldType[K, H] :: TL =
          field[K](default.head.value) :: tailBuilder.actualDefault(default.tail)
        def handler(
          name0: String,
          description0: DirectiveDescription,
          prefix: Option[DirectivePrefix],
          usage0: DirectiveUsage,
          examples0: Seq[DirectiveExamples],
          tags0: Seq[DirectiveTag],
          default: => Some[H] :: TD,
          names: List[DirectiveName] :: TN
        ) =
          new DirectiveHandler[FieldType[K, H] :: TL] {

            val tailBuilder0 = tailBuilder.handler(
              name0,
              description0,
              prefix,
              usage0,
              examples0,
              tags0,
              default.tail,
              names.tail
            )

            def withPrefix(name: String) = prefix match {
              case Some(prefix0) => prefix0.prefix + name
              case None          => name
            }

            def name                   = name0
            def description            = description0.description
            override def descriptionMd = description0.descriptionMd
            def usage                  = usage0.usage
            override def usageMd       = usage0.usageMd
            override def examples      = examples0.map(_.examples)
            override def tags          = tags0.map(_.tag)
            lazy val ourKeys =
              (fieldName.value.name +: names.head.map(_.name))
                .map(withPrefix)
                .flatMap { key =>
                  val key0 = DirectiveHandler.pascalCaseSplit(key.toCharArray.toList)
                    .map(_.toLowerCase(Locale.ROOT))
                    .mkString("-")
                  List(key, key0)
                }
                .distinct
            lazy val keys = ourKeys ++ tailBuilder0.keys

            private val fieldNames0: Set[String] = ourKeys.toSet
            def handleValues(scopedDirective: ScopedDirective)
              : Either[DirectiveException, ProcessedDirective[FieldType[K, H] :: TL]] =
              if (fieldNames0.contains(scopedDirective.directive.key))
                parser.value.parse(scopedDirective).map { r =>
                  ProcessedDirective(Some(field[K](r) :: actualDefault(default).tail), Nil)
                }
              else
                tailBuilder0
                  .handleValues(scopedDirective)
                  .map(_.map(tail => actualDefault(default).head :: tail))
          }
      }
  }

  implicit def generic[T, R <: HList, D <: HList, N <: HList](implicit
    lowPriority: caseapp.util.LowPriority,
    gen: LabelledGeneric.Aux[T, R],
    groupName: Annotation[DirectiveGroupName, T],
    description: Annotation[DirectiveDescription, T],
    prefix: AnnotationOption[DirectivePrefix, T],
    usage: Annotation[DirectiveUsage, T],
    examples: Annotation[
      DirectiveExamples,
      T
    ], // FIXME We want all the DirectiveExamples annotations, not just one
    tags: AnnotationOption[
      DirectiveTag,
      T
    ], // FIXME We want all the DirectiveTag annotations, not just one
    defaults: caseapp.util.Default.Aux[T, D],
    names: AnnotationList.Aux[DirectiveName, T, N],
    hlistHandler: HListDirectiveHandlerBuilder[R, D, N]
  ): DirectiveHandler[T] =
    hlistHandler.handler(
      groupName().name,
      description(),
      prefix(),
      usage(),
      Seq(examples()),
      tags().toSeq,
      defaults(),
      names()
    ).map(gen.from)

}

trait DirectiveHandlerMacros {

  class Deriver[T] {

    def derive[R <: HList, D <: HList, N <: HList](implicit
      gen: LabelledGeneric.Aux[T, R],
      groupName: Annotation[DirectiveGroupName, T],
      description: Annotation[DirectiveDescription, T],
      prefix: AnnotationOption[DirectivePrefix, T],
      usage: Annotation[DirectiveUsage, T],
      examples: Annotation[DirectiveExamples, T],
      tags: AnnotationOption[DirectiveTag, T],
      defaults: caseapp.util.Default.Aux[T, D],
      names: AnnotationList.Aux[DirectiveName, T, N],
      hlistHandler: DirectiveHandlerMacros.HListDirectiveHandlerBuilder[R, D, N]
    ): DirectiveHandler[T] = {
      implicit val lowPriority: caseapp.util.LowPriority = null
      DirectiveHandlerMacros.generic
    }

  }

  def deriver[T]: Deriver[T] = new Deriver[T]

  def derive[T, R <: HList, D <: HList, N <: HList](implicit
    gen: LabelledGeneric.Aux[T, R],
    groupName: Annotation[DirectiveGroupName, T],
    description: Annotation[DirectiveDescription, T],
    prefix: AnnotationOption[DirectivePrefix, T],
    usage: Annotation[DirectiveUsage, T],
    examples: Annotation[DirectiveExamples, T],
    tags: AnnotationOption[DirectiveTag, T],
    defaults: caseapp.util.Default.Aux[T, D],
    names: AnnotationList.Aux[DirectiveName, T, N],
    hlistHandler: DirectiveHandlerMacros.HListDirectiveHandlerBuilder[R, D, N]
  ): DirectiveHandler[T] = {
    implicit val lowPriority: caseapp.util.LowPriority = null
    DirectiveHandlerMacros.generic
  }

}
