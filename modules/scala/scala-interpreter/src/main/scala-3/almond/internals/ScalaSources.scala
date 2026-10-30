package almond.internals

import ammonite.compiler.Compiler
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.{Context, ctx}
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.util.SourceFile

import scala.collection.mutable
import scala.util.control.NonFatal

/** Scala source code, parsed without type checking: the code of cells, or sources from source JARs,
  * for what typed trees and symbols lack (doc comments, default arguments)
  */
private[internals] object ScalaSources {

  /** Parses Scala source code, in Scala 2 or Scala 3 syntax, keeping doc comments */
  def parse(name: String, content: String)(using Context): untpd.Tree = {
    val source = SourceFile.virtual(name, content)
    val ctx0   = ctx.fresh
      .setReporter(Compiler.newStoreReporter())
      .setCompilationUnit(CompilationUnit(source, mustExist = false))
    // Scala 2 syntax
    val ctx1 =
      try ctx0.setSetting(ctx0.settings.source, "3.0-migration")
      catch { case NonFatal(_) => ctx0 }
    new Parsers.Parser(source)(using ctx1).parse()
  }

  /** The source code of the default arguments of a method definition, by parameter name */
  def defaultArgs(
    d: untpd.DefDef,
    content: String
  )(using Context): Map[String, String] =
    d.paramss.flatten.collect {
      case p: untpd.ValDef
          if !p.rhs.isEmpty && p.rhs.span.exists && p.rhs.span.end <= content.length =>
        val code = content.substring(p.rhs.span.start, p.rhs.span.end).replaceAll("\\s+", " ")
        p.name.toString -> (if (code.length > 60) code.take(57) + "..." else code)
    }.toMap

  /** The definitions of methods named `name` in a tree */
  def defDefs(tree: untpd.Tree, name: String)(using Context): List[untpd.DefDef] = {
    val res = new mutable.ListBuffer[untpd.DefDef]
    new untpd.UntypedTreeTraverser {
      override def traverse(tree: untpd.Tree)(using Context): Unit =
        tree match {
          case d: untpd.DefDef if d.name.toString == name =>
            res += d
            traverseChildren(tree)
          case _ =>
            traverseChildren(tree)
        }
    }.traverse(tree)
    res.toList
  }
}
