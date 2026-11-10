// Based on https://github.com/scala-cli/directive-handler/blob/9e6bb77cf83581bfde42fd75d515a210995d058e/directive-handler/src/main/scala/scala/cli/directivehandler/DirectiveHandlerMacros.scala3.scala

package almond.directivehandler

import java.util.Locale

import almond.directivehandler.EitherSequence._
import scala.deriving.*
import scala.quoted.{_, given}

object DirectiveHandlerMacros {

  private def fields[U](using
    q: Quotes,
    t: Type[U]
  ): List[(q.reflect.Symbol, q.reflect.TypeRepr)] = {
    import quotes.reflect.*
    val tpe = TypeRepr.of[U]
    val sym = TypeRepr.of[U] match {
      case AppliedType(base, params) =>
        base.typeSymbol
      case _ =>
        TypeTree.of[U].symbol
    }

    // Many things inspired by https://github.com/plokhotnyuk/jsoniter-scala/blob/8f39e1d45fde2a04984498f036cad93286344c30/jsoniter-scala-macros/shared/src/main/scala-3/com/github/plokhotnyuk/jsoniter_scala/macros/JsonCodecMaker.scala#L564-L613
    // and around, here

    def typeArgs(tpe: TypeRepr): List[TypeRepr] = tpe match
      case AppliedType(_, typeArgs) => typeArgs.map(_.dealias)
      case _                        => Nil

    def resolveParentTypeArg(
      child: Symbol,
      fromNudeChildTarg: TypeRepr,
      parentTarg: TypeRepr,
      binding: Map[String, TypeRepr]
    ): Map[String, TypeRepr] =
      if (fromNudeChildTarg.typeSymbol.isTypeParam) { // todo: check for paramRef instead ?
        val paramName = fromNudeChildTarg.typeSymbol.name
        binding.get(paramName) match
          case None => binding.updated(paramName, parentTarg)
          case Some(oldBinding) =>
            if (oldBinding =:= parentTarg) binding
            else sys.error(
              s"Type parameter $paramName in class ${child.name} appeared in the constructor of " +
                s"${tpe.show} two times differently, with ${oldBinding.show} and ${parentTarg.show}"
            )
      }
      else if (fromNudeChildTarg <:< parentTarg)
        binding // TODO: assupe parentTag is covariant, get covariance from tycon type parameters.
      else
        (fromNudeChildTarg, parentTarg) match
          case (AppliedType(ctycon, ctargs), AppliedType(ptycon, ptargs)) =>
            ctargs.zip(ptargs).foldLeft(resolveParentTypeArg(child, ctycon, ptycon, binding)) {
              (b, e) =>
                resolveParentTypeArg(child, e._1, e._2, b)
            }
          case _ =>
            sys.error(s"Failed unification of type parameters of ${tpe.show} from child $child - " +
              s"${fromNudeChildTarg.show} and ${parentTarg.show}")

    def resolveParentTypeArgs(
      child: Symbol,
      nudeChildParentTags: List[TypeRepr],
      parentTags: List[TypeRepr],
      binding: Map[String, TypeRepr]
    ): Map[String, TypeRepr] =
      nudeChildParentTags.zip(parentTags).foldLeft(binding)((s, e) =>
        resolveParentTypeArg(child, e._1, e._2, s)
      )

    val nudeSubtype      = TypeIdent(sym).tpe
    val baseConst        = nudeSubtype.memberType(sym.primaryConstructor)
    val tpeArgsFromChild = typeArgs(tpe)
    val const = baseConst match {
      case MethodType(_, _, resTp) => resTp
      case PolyType(names, _, resPolyTp) =>
        val targs     = typeArgs(tpe)
        val tpBinding = resolveParentTypeArgs(sym, tpeArgsFromChild, targs, Map.empty)
        val ctArgs = names.map { name =>
          tpBinding.get(name).getOrElse(sys.error(
            s"Type parameter $name of $sym can't be deduced from " +
              s"type arguments of ${tpe.show}. Please provide a custom implicitly accessible codec for it."
          ))
        }
        val polyRes = resPolyTp match
          case MethodType(_, _, resTp) => resTp
          case other                   => other // hope we have no multiple typed param lists yet.
        if (ctArgs.isEmpty) polyRes
        else polyRes match
          case AppliedType(base, _) => base.appliedTo(ctArgs)
          case AnnotatedType(AppliedType(base, _), annot) =>
            AnnotatedType(base.appliedTo(ctArgs), annot)
          case _ => polyRes.appliedTo(ctArgs)
      case other =>
        sys.error(s"Primary constructor for ${tpe.show} is not MethodType or PolyType but $other")
    }
    sym.primaryConstructor
      .paramSymss
      .flatten
      .map(f => (f, f.tree))
      .collect {
        case (sym, v: ValDef) =>
          (sym, v.tpt.tpe)
      }
  }

  def shortName[T](using Quotes, Type[T]): String = {
    val fullName = Type.show[T]
    // attempt at getting a simple name out of fullName (this is likely broken)
    fullName.takeWhile(_ != '[').split('.').last
  }

  inline private def deriveParser[T]: DirectiveHandler[T] =
    ${ deriveParserImpl[T] }
  private def deriveParserImpl[T](using q: Quotes, t: Type[T]): Expr[DirectiveHandler[T]] = {
    import quotes.reflect.*
    val tSym    = TypeTree.of[T].symbol
    val origin  = shortName[T]
    val fields0 = fields[T]

    val defaultMap: Map[String, Expr[Any]] = {
      val comp =
        if (tSym.isClassDef && !tSym.companionClass.isNoSymbol) tSym.companionClass
        else tSym
      val bodyOpt = Some(comp)
        .filter(!_.isNoSymbol)
        .map(_.tree)
        .collect {
          case cd: ClassDef => cd.body
        }
      bodyOpt match {
        case Some(body) =>
          val names = fields0
            .map(_._1)
            .filter(_.flags.is(Flags.HasDefault))
            .map(_.name)
          val values = body.collect {
            case d @ DefDef(name, _, _, _) if name.startsWith("$lessinit$greater$default") =>
              Ref(d.symbol).asExpr
          }
          names.zip(values).toMap
        case None =>
          Map.empty
      }
    }

    val nameValue = tSym.annotations
      .find(_.tpe =:= TypeRepr.of[DirectiveGroupName])
      .collect {
        case Apply(_, List(arg)) =>
          arg.asExprOf[String]
      }
      .getOrElse {
        Expr(shortName[T].stripSuffix("Directives"))
      }

    val (usageValue, usageMdValue) = tSym.annotations
      .find(_.tpe =:= TypeRepr.of[DirectiveUsage])
      .collect {
        case Apply(_, List(arg)) =>
          (arg.asExprOf[String], Expr(""))
        case Apply(_, List(arg, argMd)) =>
          (arg.asExprOf[String], argMd.asExprOf[String])
      }
      .getOrElse {
        sys.error(s"Missing DirectiveUsage directive on ${Type.show[T]}")
      }

    val (descriptionValue, descriptionMdValue) = tSym.annotations
      .find(_.tpe =:= TypeRepr.of[DirectiveDescription])
      .collect {
        case Apply(_, List(arg)) =>
          (arg.asExprOf[String], Expr(""))
        case Apply(_, List(arg, argMd)) =>
          (arg.asExprOf[String], argMd.asExprOf[String])
      }
      .getOrElse {
        sys.error(s"Missing DirectiveDescription directive on ${Type.show[T]}")
      }

    val prefixValueOpt = tSym.annotations
      .find(_.tpe =:= TypeRepr.of[DirectivePrefix])
      .collect {
        case Apply(_, List(arg)) =>
          arg.asExprOf[String]
      }
    def withPrefix(name: Expr[String]): Expr[String] =
      prefixValueOpt match {
        case None              => name
        case Some(prefixValue) => '{ $prefixValue + $name }
      }

    val examplesValue = tSym.annotations
      .filter(_.tpe =:= TypeRepr.of[DirectiveExamples])
      .collect {
        case Apply(_, List(arg)) =>
          arg.asExprOf[String]
      }
      .reverse // not sure in what extent we can rely on the ordering here…

    val tagsValue = tSym.annotations
      .filter(_.tpe =:= TypeRepr.of[DirectiveTag])
      .collect {
        case Apply(_, List(arg)) =>
          arg.asExprOf[String]
      }
      .reverse // not sure in what extent we can rely on the ordering here…

    def namesFromAnnotations(sym: Symbol) = sym.annotations
      .filter(_.tpe =:= TypeRepr.of[DirectiveName])
      .collect {
        case Apply(_, List(arg)) =>
          withPrefix(arg.asExprOf[String])
      }

    val keysValue = Expr.ofList {
      fields0.flatMap {
        case (sym, _) =>
          withPrefix(Expr(sym.name)) +: namesFromAnnotations(sym)
      }
    }

    val elseCase: (
      Expr[ScopedDirective]
    ) => Expr[Either[DirectiveException, ProcessedDirective[T]]] =
      scopedDirective =>
        '{
          Left(new UnexpectedDirectiveError($scopedDirective.directive.key))
        }

    val handleValuesImpl = fields0.zipWithIndex.foldRight(elseCase) {
      case (((sym, tRepr), idx), elseCase0) =>
        val namesFromAnnotations0 = namesFromAnnotations(sym)

        def typeArgs(tpe: TypeRepr): List[TypeRepr] = tpe match
          case AppliedType(_, typeArgs) => typeArgs.map(_.dealias)
          case _                        => Nil

        // from https://github.com/plokhotnyuk/jsoniter-scala/blob/1704a9cbb22b75a59f21ddf2a11427ba24df3212/jsoniter-scala-macros/shared/src/main/scala-3/com/github/plokhotnyuk/jsoniter_scala/macros/JsonCodecMaker.scala#L849-L854
        def genNew(argss: List[List[Term]]): Term =
          val constructorNoTypes = Select(New(Inferred(TypeRepr.of[T])), tSym.primaryConstructor)
          val constructor = typeArgs(TypeRepr.of[T]) match
            case Nil      => constructorNoTypes
            case typeArgs => TypeApply(constructorNoTypes, typeArgs.map(Inferred(_)))
          argss.tail.foldLeft(Apply(constructor, argss.head))((acc, args) => Apply(acc, args))

        val newArgs = fields0.map {
          case (sym, _) =>
            defaultMap.getOrElse(sym.name, sys.error(s"Field ${sym.name} has no default value"))
        }

        tRepr.asType match {
          case '[t] =>
            val parser = Expr.summon[DirectiveValueParser[t]].getOrElse {
              sys.error(s"Cannot get implicit DirectiveValueParser[${Type.show[t]}]")
            }

            val name = withPrefix(Expr(sym.name))

            val cond: Expr[String] => Expr[Boolean] =
              if (namesFromAnnotations0.isEmpty)
                keyName => '{ DirectiveHandler.normalizeName($keyName) == $name }
              else {
                val names = Expr.ofList(name +: namesFromAnnotations0)
                keyName => '{ $names.contains(DirectiveHandler.normalizeName($keyName)) }
              }

            scopedDirective =>
              '{
                if (${ cond('{ $scopedDirective.directive.key }) })
                  $parser.parse($scopedDirective).map { r =>
                    ProcessedDirective(
                      Some(${
                        genNew(List(newArgs.updated(idx, '{ r }).map(_.asTerm))).asExprOf[T]
                      }),
                      Nil
                    )
                  }
                else
                  ${ elseCase0(scopedDirective) }
              }
        }
    }

    '{
      new DirectiveHandler[T] {
        def name  = $nameValue
        def usage = $usageValue
        override def usageMd =
          Some($usageMdValue).filter(_.nonEmpty).getOrElse(usage)
        def description = $descriptionValue
        override def descriptionMd =
          Some($descriptionMdValue).filter(_.nonEmpty).getOrElse(description)
        override def examples = ${ Expr.ofList(examplesValue) }
        override def tags     = ${ Expr.ofList(tagsValue) }

        lazy val keys = $keysValue
          .flatMap(key =>
            List(
              key,
              DirectiveHandler.pascalCaseSplit(key.toCharArray.toList)
                .map(_.toLowerCase(Locale.ROOT))
                .mkString("-")
            )
          )
          .distinct

        def handleValues(scopedDirective: ScopedDirective) =
          ${ handleValuesImpl('{ scopedDirective }) }
      }
    }
  }

}

trait DirectiveHandlerMacros {

  inline given derive[T]: DirectiveHandler[T] =
    DirectiveHandlerMacros.deriveParser[T]

  class Deriver[T] {

    inline given derive: DirectiveHandler[T] =
      DirectiveHandlerMacros.deriveParser[T]

  }

  def deriver[T]: Deriver[T] = new Deriver[T]

}
