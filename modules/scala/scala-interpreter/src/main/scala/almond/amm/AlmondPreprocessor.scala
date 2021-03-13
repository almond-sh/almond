package almond.amm

import ammonite.compiler.DefaultPreprocessor
import ammonite.util.Name
import fastparse.Parsed

import scala.reflect.internal.Flags
import scala.tools.nsc.{Global => G}
import scala.util.Properties

object AlmondPreprocessor {

  private[almond] val isAtLeast_2_12_7 = {
    val v = Properties.versionNumberString
    !v.startsWith("2.11.") && (!v.startsWith("2.12.") || {
      v.stripPrefix("2.12.").takeWhile(_.isDigit).toInt >= 7
    })
  }

  def customPprintSignature(ident: String, customMsg: Option[String], modOpt: Option[String], modErrOpt: Option[String]) = {
    val customCode = customMsg.fold("_root_.scala.None")(x => s"""_root_.scala.Some("$x")""")
    val modOptCode = modOpt.fold("_root_.scala.None")(x => s"""_root_.scala.Some($x)""")
    val modErrOptCode = modErrOpt.fold("_root_.scala.None")(x => s"""_root_.scala.Some($x)""")
    s"""_root_.almond
       |  .api
       |  .JupyterAPIHolder
       |  .value
       |  .Internal
       |  .printOnChange($ident, ${fastparse.internal.Util.literalize(ident)}, $customCode, $modOptCode, $modErrOptCode)""".stripMargin
  }

}

class AlmondPreprocessor(
  parse: => String => Either[String, Seq[G#Tree]],
  autoUpdateLazyVals: Boolean,
  autoUpdateVars: Boolean,
  variableInspectorEnabled: () => Boolean
) extends DefaultPreprocessor(parse) {

  // useful when debugging
  // this prints the code after pre-processing, that is the code that is actually passed to scalac for compilation
  // import ammonite.util.Util.CodeSource
  // import ammonite.util.Imports
  // import ammonite.interp.CodeWrapper
  // override def transform(
  //   stmts: Seq[String],
  //   resultIndex: String,
  //   leadingSpaces: String,
  //   codeSource: CodeSource,
  //   indexedWrapperName: Name,
  //   imports: Imports,
  //   printerTemplate: String => String,
  //   extraCode: String,
  //   skipEmpty: Boolean,
  //   markScript: Boolean,
  //   codeWrapper: CodeWrapper
  // ) = {
  //   val r = super.transform(
  //     stmts,
  //     resultIndex,
  //     leadingSpaces,
  //     codeSource,
  //     indexedWrapperName,
  //     imports,
  //     printerTemplate,
  //     extraCode,
  //     skipEmpty,
  //     markScript,
  //     codeWrapper
  //   )
  //   r.map { o =>
  //     log.info(s"Compiling '${o.code}'")
  //   }
  //   r
  // }

  import AlmondPreprocessor._

  val CustomLazyDef = Processor {
    case (_, code, t: G#ValDef)
      if autoUpdateLazyVals &&
        !DefaultPreprocessor.isPrivate(t) &&
        !t.name.decoded.contains("$") &&
        t.mods.hasFlag(Flags.LAZY) =>
      val (code0, modOpt) = fastparse.parse(code, AlmondParsers.PatVarSplitter(_)) match {
        case Parsed.Success((lhs, tpeOpt, rhs), _) if lhs.startsWith("lazy val ") =>
          val mod = Name.backtickWrap(t.name.decoded + "$value")
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
        isAtLeast_2_12_7 && // https://github.com/scala/bug/issues/10886
        !DefaultPreprocessor.isPrivate(t) &&
        !t.name.decoded.contains("$") &&
        !t.mods.hasFlag(Flags.LAZY) =>
      val (code0, modOpt) = fastparse.parse(code, AlmondParsers.PatVarSplitter(_)) match {
        case Parsed.Success((lhs, tpeOpt, rhs), _) if lhs.startsWith("var ") =>
          val mod = Name.backtickWrap(t.name.decoded + "$value")
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
           |  .declareVariable(${fastparse.internal.Util.literalize(ident)}, $ident, "[lazy]")""".stripMargin
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
             |  .declareVariable(${fastparse.internal.Util.literalize(ident)}, $ident, "[def]")""".stripMargin
        Some(extraCode0)
      } else
        None
    case(_, _, t: G#Import) => None
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

  private val baseDecls = Seq[(String, String, G#Tree) => Option[DefaultPreprocessor.Expanded]](
    CustomLazyDef, CustomVarDef,
    // same as super.decls
    ObjectDef, ClassDef, TraitDef, DefDef, TypeDef, PatVarDef, Import, Expr
  )

  override val decls = baseDecls.map { f =>
    (a: String, code: String, t: G#Tree) =>
      val resOpt = f(a, code, t)
      def withExtra = {
        val extraOpt = extraCode(a, code, t)
        (resOpt, extraOpt) match {
          case (None, _) => None
          case (Some(res), None) => Some(res)
          case (Some(res), Some(extra)) =>
            Some(res.copy(printer = s"{ $extra; Iterator() }" +: res.printer))
        }
      }
      if (variableInspectorEnabled()) withExtra
      else resOpt
  }
}
