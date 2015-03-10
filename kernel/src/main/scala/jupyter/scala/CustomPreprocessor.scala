package jupyter.scala

import _root_.ammonite.repl.Res
import org.parboiled2.ParseError

import _root_.scala.reflect.internal.Flags
import _root_.scala.tools.nsc.Global

/**
 * Converts REPL-style snippets into full-fledged Scala source files,
 * ready to feed into the compiler. Each source-string is turned into
 * three things:
 */
trait CustomPreprocessor{
  def apply(code: String, wrapperId: Int): Res[CustomPreprocessor.Output]
}
object CustomPreprocessor{

  case class Output(code: String, defined: Seq[String])

  def apply(parse: => String => Either[String, Seq[Global#Tree]]): CustomPreprocessor = new CustomPreprocessor{
    def Processor(cond: PartialFunction[(String, String, Global#Tree), Output]) = {
      (code: String, name: String, tree: Global#Tree) => cond.lift(name, code, tree)
    }

    def DefProcessor(definitionLabel: String)(cond: PartialFunction[Global#Tree, String]) =
      (code: String, name: String, tree: Global#Tree) =>
        cond.lift(tree).map{
          name => Output(code, Seq())
        }

    val ObjectDef = DefProcessor("object"){case m: Global#ModuleDef => m.name.toString}
    val ClassDef = DefProcessor("class"){
      case m: Global#ClassDef if !m.mods.hasFlag(Flags.TRAIT)=> m.name.toString
    }
    val TraitDef =  DefProcessor("trait"){
      case m: Global#ClassDef if m.mods.hasFlag(Flags.TRAIT) => m.name.toString
    }

    val DefDef = DefProcessor("function"){case m: Global#DefDef => m.name.toString}
    val TypeDef = DefProcessor("type"){case m: Global#TypeDef => m.name.toString}

    val PatVarDef = Processor { case (name, code, t: Global#ValDef) =>
      Output(
        code,
        // Try to leave out all synthetics; we don't actually have proper
        // synthetic flags right now, because we're dumb-parsing it and not putting
        // it through a full compilation
        if (t.name.decoded.contains("$")) Nil
        else if (!t.mods.hasFlag(Flags.LAZY)) Seq(t.name.toString)
        else Nil
      )
    }

    val Expr = Processor{ case (name, code, tree) =>
      Output(s"val $name = ($code)", Seq(name))
    }
    val Import = Processor{ case (name, code, tree: Global#Import) =>
      Output(code, Seq())
    }

    val decls = Seq[(String, String, Global#Tree) => Option[Output]](
      ObjectDef, ClassDef, TraitDef, DefDef, TypeDef, PatVarDef, Import, Expr
    )

    def apply(code: String, wrapperId: Int): Res[Output] = {
      val splitted = new scalaParser.Scala(code){
        def Split = {
          def Prelude = rule( Annot.* ~ `implicit`.? ~ `lazy`.? ~ LocalMod.* )
          rule( Semis.? ~ capture(Import | Prelude ~ BlockDef | StatCtx.Expr).*(Semis) ~ Semis.? ~ EOI )
        }
      }

      splitted.Split.run() match {
        case util.Failure(ParseError(p, pp, t)) if p.index == code.length => Res.Buffer(code)
        case util.Failure(e) => Res.Failure(parse(code).left.get)
        case util.Success(Nil) => Res.Skip
        case util.Success(postSplit: Seq[String]) =>

          val reParsed = postSplit.map(p => (parse(p), p))
          val errors = reParsed.collect{case (Left(e), _) => e }
          if (errors.length != 0) Res.Failure(errors.mkString("\n"))
          else {
            val allDecls = for (((Right(trees), code), i) <- reParsed.zipWithIndex) yield {
              // Suffix the name of the result variable with the index of
              // the tree if there is more than one statement in this command
              val suffix = if (reParsed.length > 1) "_" + i else ""
              def handleTree(t: Global#Tree) = {
                decls.iterator.flatMap(_.apply(code, "res" + wrapperId + suffix, t)).next()
              }
              trees match {
                // AFAIK this can only happen for pattern-matching multi-assignment,
                // which for some reason parse into a list of statements. In such a
                // scenario, aggregate all their printers, but only output the code once
                case Seq(tree) => handleTree(tree)
                case trees =>
                  val printers = for {
                    tree <- trees
                    if tree.isInstanceOf[Global#ValDef]
                    Output(_, printers) = handleTree(tree)
                    printer <- printers
                  } yield printer
                  Output(code, printers)
              }
            }

            Res(
              allDecls.reduceOption { (a, b) =>
                Output(
                  a.code + ";" + b.code,
                  a.defined ++ b.defined
                )
              },
              "Don't know how to handle " + code
            )
          }
      }
    }
  }
}

