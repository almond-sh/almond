package almond.internals

import almond.interpreter.Inspection
import almond.interpreter.api.DisplayData
import almond.logger.LoggerContext
import ammonite.runtime.Frame
import ammonite.util.Util.newLine
import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.core.Comments.{Comment, ContextDocstrings, docCtx}
import dotty.tools.dotc.core.Contexts.{Context, ctx}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.core.Symbols.{NoSymbol, Symbol}
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.util.Spans.Span

import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

final class ScalaInterpreterInspections(
  logCtx: LoggerContext,
  metabrowse: Boolean,
  metabrowseHost: String,
  metabrowsePort: Int,
  scalaVersion: String,
  compilerManager: => ammonite.compiler.CompilerLifecycleManager,
  frames: => List[Frame]
) {

  private val log = logCtx(getClass)

  private var sourceDocsInitialized = false
  private lazy val sourceDocs = {
    sourceDocsInitialized = true
    new SourceDocs(frames.head.classloader, log)
  }

  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    try inspect0(code, pos)
    catch {
      // This module is compiled against a given Scala 3 compiler, but can run alongside
      // more recent ones, whose internals we rely on here may have changed. Such changes
      // should cost an inspection, not the whole kernel.
      // Other errors, from the compiler internals or from the source JARs we read (which
      // may be closed by a concurrent shutdown), also only cost an inspection.
      case e @ (NonFatal(_) | _: LinkageError) =>
        log.warn(s"Error inspecting '${code.take(pos)}|${code.drop(pos)}'", e)
        None
    }

  private def inspect0(code: String, pos: Int): Option[Inspection] = {
    val compilerManager0 = compilerManager
    compilerManager0.init()
    val compiler = compilerManager0.compiler

    val prefix  = frames.head.imports.toString() + newLine + "object InspectWrapper{" + newLine
    val suffix  = newLine + "}"
    val allCode = prefix + code + suffix
    val index   = prefix.length + pos

    val typeChecked =
      try
        Some(
          ScalaInterpreterCompletions.tryTypeCheck(
            compiler.compiler,
            compiler.initialCtx,
            allCode.getBytes(StandardCharsets.UTF_8),
            "<inspect>"
          )
        )
      catch {
        case NonFatal(e) =>
          log.warn(s"Error type checking '${code.take(pos)}|${code.drop(pos)}'", e)
          None
      }

    typeChecked.flatMap { (unit, ctx0) =>
      val tree = unit.tpdTree
      // no line breaks in the middle of long types
      given Context = ctx0.fresh.setSetting(ctx0.settings.pageWidth, 100000)
      lazy val cellTree =
        try Some(ScalaSources.parse("<inspect>", allCode))
        catch { case NonFatal(_) => None }
      // only keep the trees from the user code, not those of the wrapper around it
      val fullPath = Interactive.pathTo(tree, Span(index))
        .takeWhile(t => t.span.exists && t.span.start >= prefix.length)
      val path       = ScalaInterpreterInspections.focus(fullPath, index)
      val derivedOpt = ScalaInterpreterInspections.derivedInstance(path, allCode, index)
      def signature(sym: Symbol, tpe: Type) =
        this.signature(sym, tpe, allCode, cellTree)
      ScalaInterpreterInspections.typeOfPath(path) match {
        case None if derivedOpt.nonEmpty =>
          val (instance, typeclass) = derivedOpt.get
          val typeStr               = instance.info.finalResultType.widen.show
          Some(inspection(code, pos, path, List(typeclass), None, typeStr))
        case Some(tpe) =>
          val symbols = ScalaInterpreterInspections.symbols(path, index)
          val signatureOpt = symbols
            .headOption
            // not when the method is only related to the tree under the cursor, like the
            // constructor called in `new Foo(…)` when the cursor is on `Foo`
            .filter(sym => path.headOption.exists(_.symbol == sym))
            .flatMap(signature(_, tpe))
          Some(inspection(code, pos, path, symbols, signatureOpt.map(List(_)), tpe.show))
        case None =>
          // Overloaded methods, whose alternative can't be picked yet, like in `Math.max(|`
          val alternatives = ScalaInterpreterInspections.overloads(path)
          if (alternatives.isEmpty) None
          else {
            val signatures = alternatives.flatMap(signature(_, _))
            Some(inspection(code, pos, path, alternatives.map(_._1), Some(signatures), ""))
          }
      }
    }
  }

  // The signature of methods, like `map[B](f: Int => B): List[B]`, with their default
  // arguments, which method types don't have: those are read from the sources (the ones of
  // the current cell, or source JARs), and shown as `...` when these can't be found
  private def signature(
    sym: Symbol,
    tpe: Type,
    cellCode: String,
    cellTree: => Option[untpd.Tree]
  )(using Context): Option[String] =
    if (!sym.is(Flags.Method)) None
    else {
      def safe[T](default: T)(f: => T): T =
        try f
        catch { case NonFatal(_) | _: LinkageError => default }
      val hasDefault = safe(Set.empty[String]) {
        sym.paramSymss.flatten.filter(_.is(Flags.HasDefault)).map(_.name.toString).toSet
      }
      val defaults =
        if (hasDefault.isEmpty) Map.empty[String, String]
        else if (ScalaInterpreterInspections.isInCell(sym))
          safe(Map.empty[String, String]) {
            cellTree.toList
              .flatMap(ScalaSources.defDefs(_, sym.name.toString))
              .headOption
              .fold(Map.empty[String, String])(ScalaSources.defaultArgs(_, cellCode))
          }
        else safe(Map.empty[String, String])(sourceDocs.defaultArgs(sym))
      // class files don't have the parameter names of Java methods (x$0, x$1, …)
      val javaParamNames =
        if (sym.is(Flags.JavaDefined))
          safe(Option.empty[List[String]])(sourceDocs.javaParamNames(sym))
        else None
      val name = if (sym.isConstructor) sym.owner.name.show else sym.name.show
      Some(
        InspectionOutput.signature(
          name,
          tpe,
          javaParamNames,
          p => if (hasDefault(p)) Some(defaults.getOrElse(p, "...")) else None
        )
      )
    }

  private def inspection(
    code: String,
    pos: Int,
    path: List[tpd.Tree],
    symbols: List[Symbol],
    signatures: Option[List[String]],
    typeStr: String
  )(using Context): Inspection = {
    val docstringOpt =
      try ScalaInterpreterInspections.docstring(path, symbols, sourceDocs)
      catch {
        // LinkageError: see inspect
        case e @ (NonFatal(_) | _: LinkageError) =>
          log.warn(s"Ignoring exception when trying to get scaladoc of '$code' at $pos", e)
          None
      }

    log.debug(s"Docstring for '${code.take(pos)}|${code.drop(pos)}' is $docstringOpt")

    val clean = InspectionOutput.withoutWrappers
    val fields =
      signatures.filter(_.nonEmpty).map { sigs =>
        // blank lines between multi-line signatures of overloaded methods
        val sep = if (sigs.exists(_.contains('\n'))) newLine + newLine else newLine
        "Signature" -> sigs.map(clean).mkString(sep)
      }
        .getOrElse("Type" -> clean(typeStr)) +:
        (docstringOpt.map("Docstring" -> _).toSeq ++
          symbols.headOption.flatMap(InspectionOutput.definedIn)
            .map("Defined in" -> _).toSeq)

    // Like IPython, only send text/plain, with ANSI colors, that front-ends like JupyterLab
    // render the same way as the inspections of the Python kernel. The VS Code Jupyter
    // PowerToys Contextual Help panel also requires a text/plain entry (it reports replies
    // without one as "No response from kernel").
    Inspection.fromDisplayData(
      DisplayData.text(InspectionOutput.fields(fields))
    )
  }

  def shutdown(): Unit =
    if (sourceDocsInitialized)
      sourceDocs.close()
}

object ScalaInterpreterInspections {

  // Jupyter front-ends send the cursor position, which is often right after an opening
  // parenthesis, like in `xs.map(|`: like IPython, inspect the called method there, rather
  // than the whole (erroneous) application
  private def focus(path: List[tpd.Tree], index: Int)(using Context): List[tpd.Tree] = {
    // The cursor needs to be strictly after the start of an argument to be in it: the
    // parser puts a `???` starting at the cursor where an argument is missing, like in
    // `xs.map(|`, and inspecting `f` in `f(|x)` is fine
    def inArgs(app: tpd.Apply) =
      app.args.exists { arg =>
        arg.span.exists && arg.span.start < index && index <= arg.span.end &&
        (arg.tpe ne null) && !arg.tpe.isError
      }
    // Like IPython, on something that has no name, like the literal in `f("abc")`, inspect
    // the method it's passed to rather than the literal itself
    val literal = path.headOption.collect { case l: tpd.Literal => l }
    def literalArg(app: tpd.Apply) =
      literal.exists(l => app.args.exists(a => a.span.exists && a.span.contains(l.span)))
    val appIdx = path.indexWhere {
      case app: tpd.Apply =>
        app.fun.span.exists && index > app.fun.span.end && (!inArgs(app) || literalArg(app))
      case _ =>
        false
    }
    if (appIdx < 0) path
    else
      path(appIdx) match {
        case app: tpd.Apply =>
          val fun = app.fun match {
            // show the declared type parameters, rather than the ones inferred for
            // the (possibly incomplete) arguments
            case tpd.TypeApply(fun0, _) => fun0
            case fun0                   => fun0
          }
          fun :: path.drop(appIdx)
        case _ =>
          path
      }
  }

  // `derives` clauses don't have typed trees: the innermost tree around them is the class
  // template. Find the given instance they generate in the companion object instead, like
  // `derived$AsObject` for `derives Codec.AsObject`, along with its type class.
  private def derivedInstance(
    path: List[tpd.Tree],
    source: String,
    index: Int
  )(using Context): Option[(Symbol, Symbol)] =
    path match {
      case (_: tpd.Template) :: (cls: tpd.TypeDef) :: _ if cls.symbol.isClass =>
        def isIdentChar(c: Char) = Character.isJavaIdentifierPart(c)
        val start = source.lastIndexWhere(c => !isIdentChar(c), (index - 1) max 0) + 1
        val end = source.indexWhere(c => !isIdentChar(c), index) match {
          case -1  => source.length
          case idx => idx
        }
        val name      = if (start < end) source.substring(start, end) else ""
        val companion = cls.symbol.companionModule
        if (name.isEmpty || !companion.exists) None
        else {
          val instance  = companion.info.member(termName("derived$" + name)).symbol
          val typeclass = instance.info.finalResultType.widen.typeSymbol
          if (instance.exists && typeclass.exists) Some((instance, typeclass)) else None
        }
      case _ =>
        None
    }

  private def symbols(path: List[tpd.Tree], index: Int)(using Context): List[Symbol] =
    path.headOption match {
      // enclosingSourceSymbols would return the symbol of an enclosing tree, whose docs
      // have nothing to do with the literal
      case Some(_: tpd.Literal) => Nil
      case _ =>
        try
          Interactive.enclosingSourceSymbols(path, ctx.source.atSpan(Span(index)))
            .filter(_.exists)
        catch {
          case NonFatal(_) => Nil
        }
    }

  // The alternatives of an overloaded method selected under the cursor, along with their
  // types as seen from the qualifier
  private def overloads(path: List[tpd.Tree])(using Context): List[(Symbol, Type)] =
    path.headOption match {
      case Some(sel: tpd.Select)
          if (sel.qualifier.tpe ne null) && sel.qualifier.tpe.exists &&
          !sel.qualifier.tpe.isError =>
        val qualTpe = sel.qualifier.tpe.widen
        qualTpe
          .member(sel.name)
          .alternatives
          .map(_.symbol)
          .filter(_.exists)
          .map(sym => (sym, qualTpe.memberInfo(sym)))
      case _ =>
        Nil
    }

  private def isInCell(sym: Symbol)(using Context): Boolean =
    sym.ownersIterator.exists(_.name.toString.startsWith("InspectWrapper"))

  // mirrors typeOfTree in the Scala 2 implementation
  private def typeOfPath(path: List[tpd.Tree])(using Context): Option[Type] =
    path.headOption.flatMap {
      case t: tpd.DefDef if t.symbol.exists =>
        Some(t.symbol.info)
      case t: tpd.ValDef if t.tpt.tpe.exists && !t.tpt.tpe.isError =>
        Some(t.tpt.tpe.widen)
      case t if (t.tpe ne null) && t.tpe.exists && !t.tpe.isError && t.tpe.widen.exists =>
        Some(t.tpe.widen)
      case _ =>
        None
    }

  // Doc comments of the symbols compiled from source are recorded by the Namer. Those of
  // symbols coming from Scala 3 libraries are read from TASTy, thanks to -Xread-docs (see
  // AlmondCompilerLifecycleManager.extraInitialSettings). Libraries compiled by Scala 2
  // (like the standard library before Scala 3.8) and Java libraries have no docs there:
  // those are read from source JARs (see SourceDocs). All of them are rendered by DocText.
  // Definitions from previous cells have no docs at all: the Ammonite statement splitter
  // drops the comment preceding them.
  private def docstring(
    path: List[tpd.Tree],
    symbols: List[Symbol],
    sourceDocs: SourceDocs
  )(using Context): Option[String] = {
    def candidates(sym: Symbol): Iterator[Symbol] =
      if (sym.exists) {
        val sym0 = if (sym.isPrimaryConstructor) sym.owner else sym
        Iterator(sym0) ++ Iterator(sym0.moduleClass).filter(_.exists) ++
          sym0.allOverriddenSymbols
      }
      else
        Iterator.empty
    val fromTasty = for {
      docCtx <- ctx.docCtx
      doc <- symbols
        .iterator
        .flatMap(candidates)
        .flatMap(sym => docCtx.docstring(sym).map((sym, _)))
        .map { (sym, comment) =>
          val expanded = expand(docCtx, comment, sym, site(path, sym))
          DocText.fromScaladoc(expanded.expandedBody.getOrElse(expanded.raw)).render
        }
        .find(_.nonEmpty)
    } yield doc
    fromTasty.orElse {
      symbols
        .iterator
        .flatMap(sym => sourceDocs.docstring(candidates(sym).toList, site(path, sym)))
        .nextOption()
    }
  }

  // The class whose scaladoc variables ($coll, …) are substituted in the doc of sym:
  // the one of the qualifier in `qual.sym`, so that List(…).map gets the List variables
  private def site(path: List[tpd.Tree], sym: Symbol)(using Context): Symbol = {
    val qualifierClass = path.headOption match {
      case Some(sel: tpd.Select) if sel.qualifier.tpe.exists =>
        sel.qualifier.tpe.widen.classSymbol
      case _ =>
        NoSymbol
    }
    if (qualifierClass.exists) qualifierClass else sym.owner
  }

  // Like dotty.tools.dotc.typer.Docstrings.cookComment, minus the typing of @usecase
  // sections, which would enter symbols in the compiler, and minus the caching of the
  // result, which depends on the site here.
  private def expand(
    docCtx: ContextDocstrings,
    comment: Comment,
    sym: Symbol,
    site: Symbol
  )(using Context): Comment =
    if (comment.isExpanded || !site.exists) comment
    else
      try {
        val expander  = docCtx.templateExpander
        val baseSites = if (site.isClass) site.info.baseClasses else Nil
        // CommentExpander.expand(s, s) is called for its side effect of registering the
        // @define variables of s: CommentExpander.defineVariables isn't public anymore
        // in recent Scala versions
        (sym :: site :: baseSites).distinct.foreach(s => expander.expand(s, s))
        comment.expand(expander.expandedDocComment(sym, site, _))
      }
      catch {
        case NonFatal(_) => comment // e.g. CommentExpander.ExpansionLimitExceeded
      }
}
