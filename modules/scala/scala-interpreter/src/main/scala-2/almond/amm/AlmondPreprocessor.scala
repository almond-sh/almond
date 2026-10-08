package almond.amm

import ammonite.compiler.DefaultPreprocessor
import ammonite.util.Name
import fastparse.Parsed

import scala.reflect.internal.Flags
import scala.tools.nsc.{Global => G}

object AlmondPreprocessor {

  def customPprintSignature(
    ident: String,
    customMsg: Option[String],
    modOpt: Option[String],
    modErrOpt: Option[String]
  ) = {
    val customCode    = customMsg.fold("_root_.scala.None")(x => s"""_root_.scala.Some("$x")""")
    val modOptCode    = modOpt.fold("_root_.scala.None")(x => s"""_root_.scala.Some($x)""")
    val modErrOptCode = modErrOpt.fold("_root_.scala.None")(x => s"""_root_.scala.Some($x)""")
    s"""_root_.almond
       |  .api
       |  .JupyterAPIHolder
       |  .value
       |  .Internal
       |  .printOnChange($ident, ${fastparse.internal.Util.literalize(
        ident
      )}, $customCode, $modOptCode, $modErrOptCode)""".stripMargin
  }

}

class AlmondPreprocessor(
  parse: => String => Either[String, Seq[G#Tree]],
  autoUpdateLazyVals: Boolean,
  autoUpdateVars: Boolean,
  silentImports: Boolean,
  variableInspectorEnabled: () => Boolean,
  outputStyle: () => almond.OutputStyle,
  logCtx: almond.logger.LoggerContext,
  logCode: Boolean
) extends DefaultPreprocessor(parse) {

  // useful when debugging
  // this prints the code after pre-processing, that is the code that is actually passed to scalac for compilation
  private lazy val log = logCtx(getClass)
  override def transform(
    stmts: Seq[String],
    resultIndex: String,
    leadingSpaces: String,
    codeSource: ammonite.util.Util.CodeSource,
    indexedWrapperName: Name,
    imports: ammonite.util.Imports,
    printerTemplate: String => String,
    extraCode: String,
    skipEmpty: Boolean,
    markScript: Boolean,
    codeWrapper: ammonite.compiler.iface.CodeWrapper
  ) = {
    val printerTemplate0 =
      if (outputStyle() == almond.OutputStyle.Python)
        lastExpressionPrinter(stmts, resultIndex) match {
          case Some(printer) =>
            // the printers we get here only contain the variable inspector stuff
            (printers: String) =>
              printerTemplate(if (printers.isEmpty) printer else s"$printers, $printer")
          case None =>
            printerTemplate
        }
      else
        printerTemplate
    val r = super.transform(
      stmts,
      resultIndex,
      leadingSpaces,
      codeSource,
      indexedWrapperName,
      imports,
      printerTemplate0,
      extraCode,
      skipEmpty,
      markScript,
      codeWrapper
    )
    if (logCode)
      r.map { o =>
        val nl = System.lineSeparator()
        log.info(s"Compiling ${indexedWrapperName.encoded}.sc$nl---$nl${o.code}$nl---")
      }
    r
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
        .map { case (stmt, idx) => (parse(stmt), idx) }
        .collect { case (Right(trees), idx) if trees.nonEmpty => (trees, idx) }
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
  private def isExpression(tree: G#Tree): Boolean =
    tree match {
      case _: G#ModuleDef | _: G#ClassDef | _: G#DefDef | _: G#TypeDef | _: G#ValDef |
          _: G#Import =>
        false
      case _ =>
        true
    }

  import AlmondPreprocessor._

  val CustomLazyDef = Processor {
    case (_, code, t: G#ValDef)
        if autoUpdateLazyVals &&
        !DefaultPreprocessor.isPrivate(t) &&
        !t.name.decoded.contains("$") &&
        t.mods.hasFlag(Flags.LAZY) =>
      val (code0, modOpt) = fastparse.parse(code, AlmondParsers.PatVarSplitter(_)) match {
        case Parsed.Success((lhs, tpeOpt, rhs), _) if lhs.startsWith("lazy val ") =>
          val mod     = Name.backtickWrap(t.name.decoded + "$value")
          val tpePart = tpeOpt.fold("")(t => "[" + t + "]")
          val c = s"""val $mod = new _root_.almond.api.internal.Lazy$tpePart(() => $rhs)
                     |import $mod.{value => ${Name.backtickWrap(t.name.decoded)}}
                     |""".stripMargin
          (c, Some(mod + ".onChange"))
        case _ =>
          (code, None)
      }
      DefaultPreprocessor.Expanded(
        code0,
        Seq(customPprintSignature(Name.backtickWrap(t.name.decoded), Some("[lazy]"), None, modOpt))
      )
  }

  val CustomVarDef = Processor {

    case (_, code, t: G#ValDef)
        if autoUpdateVars &&
        AlmondCompilerLifecycleManager.isAtLeast_2_12_7 && // https://github.com/scala/bug/issues/10886
        !DefaultPreprocessor.isPrivate(t) &&
        !t.name.decoded.contains("$") &&
        !t.mods.hasFlag(Flags.LAZY) =>
      val (code0, modOpt) = fastparse.parse(code, AlmondParsers.PatVarSplitter(_)) match {
        case Parsed.Success((lhs, tpeOpt, rhs), _) if lhs.startsWith("var ") =>
          val mod     = Name.backtickWrap(t.name.decoded + "$value")
          val tpePart = tpeOpt.fold("")(t => "[" + t + "]")
          val c = s"""val $mod = new _root_.almond.api.internal.Modifiable$tpePart($rhs)
                     |import $mod.{value => ${Name.backtickWrap(t.name.decoded)}}
                     |""".stripMargin
          (c, Some(mod + ".onChange"))
        case _ =>
          (code, None)
      }
      DefaultPreprocessor.Expanded(
        code0,
        Seq(customPprintSignature(Name.backtickWrap(t.name.decoded), None, modOpt, None))
      )

  }

  val extraCode: (String, String, G#Tree) => Option[String] = {
    case (_, code, t: G#ValDef) if !t.mods.hasFlag(Flags.LAZY) =>
      val ident = Name.backtickWrap(t.name.decoded)
      val extraCode0 =
        s"""_root_.almond
           |  .api
           |  .JupyterAPIHolder
           |  .value
           |  .Internal
           |  .declareVariable(${fastparse.internal.Util.literalize(ident)}, $ident)""".stripMargin
      Some(extraCode0)
    case (_, code, t: G#ValDef) if t.mods.hasFlag(Flags.LAZY) =>
      val ident = Name.backtickWrap(t.name.decoded)
      val extraCode0 =
        s"""_root_.almond
           |  .api
           |  .JupyterAPIHolder
           |  .value
           |  .Internal
           |  .declareVariable(${fastparse.internal.Util.literalize(
            ident
          )}, $ident, "[lazy]")""".stripMargin
      Some(extraCode0)
    case (_, code, t: G#DefDef) =>
      if (t.tparams.isEmpty && t.vparamss.isEmpty) {
        val ident = Name.backtickWrap(t.name.decoded)
        val extraCode0 =
          s"""_root_.almond
             |  .api
             |  .JupyterAPIHolder
             |  .value
             |  .Internal
             |  .declareVariable(${fastparse.internal.Util.literalize(
              ident
            )}, $ident, "[def]")""".stripMargin
        Some(extraCode0)
      }
      else
        None
    case (_, _, _: G#ModuleDef) => None
    case (_, _, _: G#ClassDef)  => None
    case (_, _, _: G#TypeDef)   => None
    case (_, _, _: G#Import)    => None
    case (_, code, t) =>
      val ident = code
      val extraCode0 =
        s"""_root_.almond
           |  .api
           |  .JupyterAPIHolder
           |  .value
           |  .Internal
           |  .declareVariable(${fastparse.internal.Util.literalize(code)}, $code)""".stripMargin
      Some(extraCode0)
  }

  val SilenceImport = Processor {
    case (_, code, _: G#Import) =>
      DefaultPreprocessor.Expanded(code, Seq("_root_.scala.Iterator[String]()"))
  }

  private val baseDecls = Seq[(String, String, G#Tree) => Option[DefaultPreprocessor.Expanded]](
    CustomLazyDef,
    CustomVarDef,
    // same as super.decls, but for Import / SilenceImport
    ObjectDef,
    ClassDef,
    TraitDef,
    DefDef,
    TypeDef,
    PatVarDef,
    if (silentImports) SilenceImport else Import,
    Expr
  )

  override val decls = baseDecls.map { f => (a: String, code: String, t: G#Tree) =>
    val resOpt =
      if (outputStyle() == almond.OutputStyle.Python)
        // nothing gets printed for individual statements - transform adds
        // a printer for the last expression of the cell
        f(a, code, t).map(_.copy(printer = Nil))
      else
        f(a, code, t)
    def withExtra = {
      val extraOpt = extraCode(a, code, t)
      (resOpt, extraOpt) match {
        case (None, _)         => None
        case (Some(res), None) => Some(res)
        case (Some(res), Some(extra)) =>
          Some(res.copy(printer = s"{ $extra; _root_.scala.Iterator[String]() }" +: res.printer))
      }
    }
    if (variableInspectorEnabled()) withExtra
    else resOpt
  }
}
