package almond.amm

import ammonite.interp.{DefaultPreprocessor, Parsers}
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
  autoUpdateVars: Boolean
) extends DefaultPreprocessor(parse) {

  import AlmondPreprocessor._

  val CustomLazyDef = Processor {
    case (_, code, t: G#ValDef)
      if autoUpdateLazyVals &&
        !DefaultPreprocessor.isPrivate(t) &&
        !t.name.decoded.contains("$") &&
        t.mods.hasFlag(Flags.LAZY) =>
      val (code0, modOpt) = fastparse.parse(code, Parsers.PatVarSplitter(_)) match {
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
      println(s"t.name=${t.name}")
      println(s"t=$t")
      println(s"code=$code")
      val (code0, modOpt) = fastparse.parse(code, Parsers.PatVarSplitter(_)) match {
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
  override val decls = Seq[(String, String, G#Tree) => Option[DefaultPreprocessor.Expanded]](
    CustomLazyDef, CustomVarDef,
    // same as super.decls
    ObjectDef, ClassDef, TraitDef, DefDef, TypeDef, PatVarDef, Import, Expr
  )
}
