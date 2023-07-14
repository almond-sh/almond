package almond.internals

import almond.logger.LoggerContext
import ammonite.compiler.Compiler
import dotty.tools.dotc.{CompilationUnit, Compiler => DottyCompiler, Run, ScalacCommand}
import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Symbols.{defn, Symbol}
import dotty.tools.dotc.core.{Flags, MacroClassLoader, Mode}
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.dotc.util.{Property, SourceFile, SourcePosition}

import java.nio.charset.StandardCharsets

object ScalaInterpreterCompletions {

  extension (completion: Completion) {
    def finalLabel = completion.label.replace(".package$.", ".")
  }

  private def newLine = System.lineSeparator()

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

    val (tree, ctx0) =
      tryTypeCheck(compiler, initialCtx, allCode.getBytes("UTF-8"), "<completions>")
    val ctx  = ctx0.fresh
    val file = SourceFile.virtual("<completions>", allCode, maybeIncomplete = true)
    val unit = CompilationUnit(file)(using ctx)
    unit.tpdTree = {
      given Context = ctx
      import tpd._
      tree match {
        case PackageDef(_, p) =>
          p.collectFirst {
            case TypeDef(_, tmpl: Template) =>
              tmpl.body
                .collectFirst { case dd: ValDef if dd.name.show == "expr" => dd }
                .getOrElse(???)
          }.getOrElse(???)
        case _ => ???
      }
    }
    val ctx1   = ctx.fresh.setCompilationUnit(unit)
    val srcPos = SourcePosition(file, Span(index))
    val (start, completions) = dotty.ammonite.compiler.AmmCompletion.completions(
      srcPos,
      dependencyCompleteOpt = dependencyCompleteOpt,
      enableDeep = false
    )(using ctx1)

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
      val blacklist = Set(
        "scala.Predef.any2stringadd.+",
        "scala.Any.##",
        "java.lang.Object.##",
        "scala.<byname>",
        "scala.<empty>",
        "scala.<repeated>",
        "scala.<repeated...>",
        "scala.Predef.StringFormat.formatted",
        "scala.Predef.Ensuring.ensuring",
        "scala.Predef.ArrowAssoc.->",
        "scala.Predef.ArrowAssoc.→",
        "java.lang.Object.synchronized",
        "java.lang.Object.ne",
        "java.lang.Object.eq",
        "java.lang.Object.wait",
        "java.lang.Object.notifyAll",
        "java.lang.Object.notify",
        "java.lang.Object.clone",
        "java.lang.Object.finalize"
      )

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
  ) =
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

    val unit =
      new CompilationUnit(ctx.source):
        override def isSuspendable: Boolean = false
    ctx
      .run
      .compileUnits(unit :: Nil, ctx)

    (unit.tpdTree, ctx)
}
