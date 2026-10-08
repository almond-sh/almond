package almond.amm

import ammonite.compiler.{Compiler, DottyParser, Preprocessor}
import ammonite.util.Name
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.{Flags, Names}
import dotty.tools.dotc.parsing.Tokens
import dotty.tools.dotc.util.SourceFile

class AlmondPreprocessor(
  ctx: Context,
  autoUpdateLazyVals: Boolean,
  autoUpdateVars: Boolean,
  silentImports: Boolean,
  variableInspectorEnabled: () => Boolean,
  outputStyle: () => almond.OutputStyle,
  logCtx: almond.logger.LoggerContext,
  logCode: Boolean // FIXME Respect that
) extends Preprocessor(ctx, markGeneratedSections = false) {

  // useful when debugging
  // this prints the code after pre-processing, that is the code that is actually passed to scalac for compilation
  private lazy val log = logCtx(getClass)
  override def transform(
    stmts: Seq[String],
    resultIndex: String,
    leadingSpaces: String,
    codeSource: ammonite.util.Util.CodeSource,
    indexedWrapper: Name,
    imports: ammonite.util.Imports,
    printerTemplate: String => String,
    extraCode: String,
    skipEmpty: Boolean,
    markScript: Boolean,
    codeWrapper: ammonite.compiler.iface.CodeWrapper
  ): ammonite.util.Res[ammonite.compiler.iface.Preprocessor.Output] = {
    val declarations =
      if (variableInspectorEnabled()) declareVariablesCode(stmts, resultIndex)
      else Nil
    val extra =
      if (declarations.isEmpty) Nil
      else Seq(s"{ ${declarations.mkString("; ")}; _root_.scala.Iterator[String]() }")
    val printerTemplate0 =
      if (outputStyle() == almond.OutputStyle.Python) {
        // discarding the printers of the Ammonite preprocessor, and only printing
        // the last expression of the cell
        val printers = extra ++ lastExpressionPrinter(stmts, resultIndex).toSeq
        (_: String) => printerTemplate(printers.mkString(", "))
      }
      else if (extra.isEmpty) printerTemplate
      else
        (printers: String) =>
          printerTemplate((extra ++ Seq(printers).filter(_.nonEmpty)).mkString(", "))
    val res = super.transform(
      stmts,
      resultIndex,
      leadingSpaces,
      codeSource,
      indexedWrapper,
      imports,
      printerTemplate0,
      extraCode,
      skipEmpty,
      markScript,
      codeWrapper
    )
    if (logCode)
      res.map { o =>
        val nl = System.lineSeparator()
        log.info(s"Compiling ${indexedWrapper.encoded}.sc$nl---$nl${o.code}$nl---")
      }
    res
  }

  /** In the Python output style, code printing the result of the last statement, if it's an
    * expression and the cell doesn't end with a semicolon
    */
  private def lastExpressionPrinter(stmts: Seq[String], resultIndex: String): Option[String] =
    if (PythonOutputStyle.endsWithSemicolon(stmts.mkString)) None
    else
      stmts
        .iterator
        .zipWithIndex
        .flatMap { case (stmt, idx) => parse(stmt).filter(_.nonEmpty).map((_, idx)) }
        .toVector
        .lastOption
        .collect {
          case (Seq(tree), idx) if isExpression(tree) =>
            PythonOutputStyle.printerCode(PythonOutputStyle.resultName(
              resultIndex,
              idx,
              stmts.length
            ))
        }

  /** Whether the Ammonite preprocessor handles a tree as an expression, whose result it puts in a
    * `res…` variable
    */
  private def isExpression(tree: untpd.Tree): Boolean =
    tree match {
      case _: untpd.ModuleDef | _: untpd.TypeDef | _: untpd.DefDef | _: untpd.ValDef |
          _: untpd.PatDef | _: untpd.Import | _: untpd.Export | _: untpd.ExtMethods =>
        false
      case _ =>
        true
    }

  // Variable inspector support: the Scala 2 AlmondPreprocessor adds a call to declareVariable to the
  // printer code of each definition and expression. The Scala 3 Ammonite preprocessor can't be
  // customized per statement, so we parse the statements again here, and add the calls for all of
  // them in front of its printer code. Expression results get the same names as the ones the
  // Ammonite preprocessor gives them.

  private def parse(stmt: String): Option[List[untpd.Tree]] = {
    val reporter   = Compiler.newStoreReporter()
    val sourceFile = SourceFile.virtual("foo", stmt)
    val parseCtx   = ctx.fresh.setReporter(reporter).withSource(sourceFile)
    val parser     = new DottyParser(sourceFile)(using parseCtx)
    val trees      = parser.blockStatSeq()
    parser.accept(Tokens.EOF)
    if (reporter.hasErrors) None
    else Some(trees)
  }

  private def declareVariableCode(ident: String, strValueOpt: Option[String]): String = {
    val strValuePart = strValueOpt.fold("")(s => ", " + pprint.Util.literalize(s))
    s"""_root_.almond
       |  .api
       |  .JupyterAPIHolder
       |  .value
       |  .Internal
       |  .declareVariable(${pprint.Util.literalize(ident)}, $ident$strValuePart)""".stripMargin
  }

  /** Whether a definition should be listed in the variable inspector, and its name if it should */
  private def inspectedName(mods: untpd.Modifiers, name: Names.Name): Option[String] = {
    val decoded = name.decode.toString
    val ignore = mods.is(Flags.Private) || mods.is(Flags.Given) ||
      name.isEmpty || decoded == "_" || decoded.contains("$")
    if (ignore) None
    else Some(Name.backtickWrap(decoded))
  }

  /** The variables a statement defines, along with their value as a string if it shouldn't be
    * computed (for lazy vals and defs)
    */
  private def inspectedVariables(
    tree: untpd.Tree,
    resultName: String
  ): Seq[(String, Option[String])] =
    tree match {
      case _: untpd.ModuleDef | _: untpd.TypeDef | _: untpd.Import | _: untpd.Export |
          _: untpd.ExtMethods =>
        Nil
      case d: untpd.DefDef =>
        if (d.paramss.isEmpty) inspectedName(d.mods, d.name).map(_ -> Some("[def]")).toSeq
        else Nil
      case v: untpd.ValDef =>
        val strValueOpt = if (v.mods.is(Flags.Lazy)) Some("[lazy]") else None
        inspectedName(v.mods, v.name).map(_ -> strValueOpt).toSeq
      case p: untpd.PatDef =>
        val strValueOpt = if (p.mods.is(Flags.Lazy)) Some("[lazy]") else None
        p.pats
          .flatMap {
            case untpd.Tuple(trees) => trees
            case elem               => List(elem)
          }
          .flatMap {
            case untpd.Ident(name)                 => inspectedName(p.mods, name)
            case untpd.Typed(untpd.Ident(name), _) => inspectedName(p.mods, name)
            case _                                 => None
          }
          .map(_ -> strValueOpt)
      case _ =>
        // expression, that the Ammonite preprocessor assigns to a val named resultName
        Seq(resultName -> None)
    }

  private def declareVariablesCode(stmts: Seq[String], resultIndex: String): Seq[String] =
    stmts.zipWithIndex.flatMap {
      case (stmt, idx) =>
        // same naming as the Ammonite preprocessor
        val suffix     = if (stmts.length > 1) "_" + idx else ""
        val resultName = "res" + resultIndex + suffix
        val variables = parse(stmt).toSeq.flatMap {
          case Seq(tree) => inspectedVariables(tree, resultName)
          // multi-imports
          case trees if trees.forall(_.isInstanceOf[untpd.Import]) => Nil
          // the Ammonite preprocessor only keeps the vals when a statement parses to several trees
          case trees =>
            trees.collect { case v: untpd.ValDef => v }.flatMap(inspectedVariables(_, ""))
        }
        variables.map {
          case (ident, strValueOpt) =>
            declareVariableCode(ident, strValueOpt)
        }
    }
}
