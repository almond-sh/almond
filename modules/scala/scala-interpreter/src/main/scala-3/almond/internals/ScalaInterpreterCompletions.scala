package almond.internals

import almond.logger.LoggerContext
import ammonite.compiler.Compiler
import ammonite.compiler.internal.CompilerInternals
import dotty.tools.dotc.{CompilationUnit, Compiler => DottyCompiler, Run, ScalacCommand}
import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Symbols.{defn, Symbol}
import dotty.tools.dotc.core.{Flags, MacroClassLoader, Mode}
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.typer.ImportInfo.withRootImports
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.dotc.util.{Property, SourceFile, SourcePosition}

import java.nio.charset.StandardCharsets

object ScalaInterpreterCompletions {

  extension (completion: Completion) {
    def finalLabel = completion.label.replace(".package$.", ".")
  }

  private def newLine = System.lineSeparator()

  private val blacklist = {
    val scalaList = Seq(
      "Predef.any2stringadd.+",
      "Any.##",
      "<byname>",
      "<empty>",
      "<repeated>",
      "<repeated...>",
      "Predef.StringFormat.formatted",
      "Predef.Ensuring.ensuring",
      "Predef.ArrowAssoc.->",
      "Predef.ArrowAssoc.→"
    )
    val javaLangList = Set(
      "Object.##",
      "Object.synchronized",
      "Object.ne",
      "Object.eq",
      "Object.wait",
      "Object.notifyAll",
      "Object.notify",
      "Object.clone",
      "Object.finalize"
    )
    val it =
      Iterator("", "scala.")
        .flatMap(prefix => scalaList.iterator.map(prefix + _)) ++
        Iterator("", "java.lang.")
          .flatMap(prefix => javaLangList.iterator.map(prefix + _))
    it.toSet
  }

  def complete(
    compilerManager: ammonite.compiler.iface.CompilerLifecycleManager,
    dependencyCompleteOpt: Option[String => (Int, Seq[String])],
    snippetIndex: Int,
    previousImports: String,
    snippet: String,
    logCtx: LoggerContext
  ): (Int, Seq[String], Seq[String], Seq[(String, String)]) = {

    val compilerManager0 = compilerManager match {
      case m: ammonite.compiler.CompilerLifecycleManager => m
      case _                                             => ???
    }

    val compiler   = compilerManager0.compiler.compiler
    val initialCtx = compilerManager0.compiler.initialCtx

    val offset = snippetIndex

    val prefix = previousImports + newLine +
      "object AutocompleteWrapper{ val expr: _root_.scala.Unit = {" + newLine
    val suffix  = newLine + "()}}"
    val allCode = prefix + snippet + suffix
    val index   = offset + prefix.length

    // Originally based on
    // https://github.com/lampepfl/dotty/blob/3.0.0-M1/
    //   compiler/src/dotty/tools/repl/ReplDriver.scala/#L179-L191

    val (typeCheckedUnit, ctx0) =
      tryTypeCheck(compiler, initialCtx, allCode.getBytes("UTF-8"), "<completions>")
    val ctx  = ctx0.fresh
    val file = SourceFile.virtual("<completions>", allCode, maybeIncomplete = true)
    val unit = CompilationUnit(file)(using ctx)
    // The whole tree, rather than only the `expr` definition wrapping the snippet, like the
    // Scala 3 REPL does: the completion scope is computed from the trees enclosing the cursor,
    // which need to include the imports that precede the wrapper (those of the previous
    // cells, in particular)
    unit.tpdTree = typeCheckedUnit.tpdTree
    // Recent Scala versions also look at the untyped tree, to find the prefix to complete
    // when the typed tree around the cursor is erroneous (like the `foo` of an incomplete
    // `foo|`)
    unit.untpdTree = typeCheckedUnit.untpdTree
    // with the root imports (scala._, Predef._, …), like the Scala 3 IDE driver
    // (dotty.tools.dotc.interactive.InteractiveDriver) does
    val ctx1   = ctx.fresh.setCompilationUnit(unit).withRootImports
    val srcPos = SourcePosition(file, Span(index))
    val (start, completions) =
      try
        CompilerInternals.completionMaker.completions(
          srcPos,
          dependencyCompleteOpt = dependencyCompleteOpt,
          enableDeep = false
        )(using ctx1)
      catch {
        // The Ammonite completion internals for Scala 3.7 and 3.8 are also used for more recent
        // versions, but Completion.CompletionResult changed in Scala 3.9, so that these fail with
        // a ClassCastException there. Call the dotty completion API ourselves then (this module
        // is compiled against a Scala 3 version that has the same CompletionResult as 3.9). This
        // only loses the completion of dependencies in `import $ivy`.
        case _: ClassCastException =>
          Completion.completions(srcPos)(using ctx1)
      }

    val blacklistedPackages = Set("shaded")

    def deepCompletion(name: String): List[String] = {
      given Context = ctx1
      def rec(t: Symbol): Seq[Symbol] =
        if (blacklistedPackages(t.name.toString))
          Nil
        else {
          val children =
            if (t.is(Flags.Package) || t.is(Flags.PackageVal) || t.is(Flags.PackageClass))
              t.denot.info.allMembers.map(_.symbol).filter(_ != t).flatMap(rec)
            else Nil

          t +: children.toSeq
        }

      for {
        member <- defn.RootClass.denot.info.allMembers.map(_.symbol).toList
        sym    <- rec(member)
        // Scala 2 comment: sketchy name munging because I don't know how to do this properly
        // Note lack of back-quoting support.
        strippedName = sym.name.toString.stripPrefix("package$").stripSuffix("$")
        if strippedName.startsWith(name)
        (pref, _) = sym.fullName.toString.splitAt(sym.fullName.toString.lastIndexOf('.') + 1)
        out       = pref + strippedName
        if out != ""
      } yield out
    }

    def blacklisted(s: Symbol) = {
      given Context = ctx1

      blacklist(s.showFullName) ||
      s.isOneOf(Flags.GivenOrImplicit) ||
      // Cache objects, which you should probably never need to
      // access directly, and apart from that have annoyingly long names
      "cache[a-f0-9]{32}".r.findPrefixMatchOf(s.name.decode.toString).isDefined ||
      // s.isDeprecated ||
      s.name.decode.toString == "<init>" ||
      s.name.decode.toString.contains('$')
    }

    val filteredCompletions = completions.filter { c =>
      c.symbols.isEmpty || c.symbols.exists(!blacklisted(_))
    }
    val signatures = {
      given Context = ctx1
      for {
        c <- filteredCompletions
        s <- c.symbols
        isMethod = s.denot.is(Flags.Method)
        if isMethod
      } yield s"def ${s.name}${s.denot.info.widenTermRefExpr.show}"
    }
    val completionsWithTypes = {
      given Context = ctx1
      for {
        c <- filteredCompletions
        s <- c.symbols
      } yield (c.finalLabel, s.denot.kindString)
    }
    (
      start - prefix.length,
      filteredCompletions.map(_.finalLabel).sorted,
      signatures,
      completionsWithTypes.sorted
    )
  }

  private def tryTypeCheck(
    compiler: DottyCompiler,
    initialCtx: Context,
    src: Array[Byte],
    fileName: String
  ): (CompilationUnit, Context) =
    val sourceFile = SourceFile.virtual(fileName, new String(src, StandardCharsets.UTF_8))

    val reporter0 = Compiler.newStoreReporter()
    val run = new Run(
      compiler,
      initialCtx.fresh
        .addMode(Mode.ReadPositions | Mode.Interactive)
        .setReporter(reporter0)
        .setSetting(initialCtx.settings.YstopAfter, List("typer"))
    )
    implicit val ctx: Context = run.runContext.withSource(sourceFile)

    val unit = CompilerInternals.compilationUnitMaker.notSuspendable(ctx.source)
    ctx
      .run
      .compileUnits(unit :: Nil, ctx)

    (unit, ctx)
}
