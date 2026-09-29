package almond.internals

import ammonite.util.Util.newLine
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.{ExprType, MethodType, PolyType, Type}

import scala.collection.mutable

/** How inspections are formatted: like those of IPython, as text with ANSI colors */
private[internals] object InspectionOutput {

  def red(s: String): String  = "\u001b[0;31m" + s + "\u001b[0m"
  def bold(s: String): String = "\u001b[1m" + s + "\u001b[0m"

  /** Formats fields the same way as IPython, like
    * {{{
    * Signature: map[B](f: Int => B): List[B]
    * Docstring:
    * Builds a new list …
    * }}}
    */
  def fields(fields: Seq[(String, String)]): String = {
    val width = fields.map(_._1.length).maxOption.getOrElse(0) + 2
    fields
      .map { (title, content) =>
        val header = red(title + ":")
        if (content.contains('\n')) header + newLine + content
        else header + " " * (width - title.length - 1) + content
      }
      .mkString(newLine)
  }

  /** A section of a doc comment, like "Parameters:" followed by one line per parameter */
  def docSection(title: String, lines: Seq[String]): String =
    (bold(title + ":") +: lines).mkString(newLine)

  // Signatures longer than that get one parameter per line
  private def maxSignatureLength = 100

  /** The signature of a method, like `map[B](f: Int => B): List[B]`
    *
    * @param default
    *   the default argument of a parameter, if it has one
    */
  def signature(
    name: String,
    tpe: Type,
    default: String => Option[String]
  )(using Context): String = {
    val tparams = new StringBuilder
    val clauses = new mutable.ListBuffer[(String, List[String])]
    var result  = ""
    def loop(t: Type): Unit =
      t match {
        case pt: PolyType =>
          tparams ++= typeParamClause(pt)
          loop(pt.resType)
        case mt: MethodType =>
          val open =
            if (mt.isContextualMethod) "(using "
            else if (mt.isImplicitMethod) "(implicit "
            else "("
          val names = mt.paramNames.map(_.toString)
          val params = names.zip(mt.paramInfos).map { (n, info) =>
            s"$n: ${info.show}" + default(n).fold("")(" = " + _)
          }
          clauses += ((open, params))
          loop(mt.resType)
        case et: ExprType =>
          result = et.resType.show
        case other =>
          result = other.show
      }
    loop(tpe)
    val resultPart = if (result.isEmpty) "" else ": " + result
    val oneLine =
      name + tparams + clauses.map((open, ps) => open + ps.mkString(", ") + ")").mkString +
        resultPart
    if (oneLine.length <= maxSignatureLength) oneLine
    else
      name + tparams + clauses.map { (open, ps) =>
        if (ps.isEmpty) open.trim + ")"
        else
          open.trim + newLine + ps.map("  " + _).mkString("," + newLine) + newLine + ")"
      }.mkString + resultPart
  }

  // The leading type parameter clause of a polymorphic method type, like `[B >: A]`
  private def typeParamClause(pt: PolyType)(using Context): String = {
    val str   = pt.show
    var depth = 0
    val end = str.indexWhere { c =>
      if (c == '[') depth += 1
      else if (c == ']') depth -= 1
      depth == 0
    }
    if (str.startsWith("[") && end > 0) str.take(end + 1) else ""
  }

  // The prefixes of the types defined in the current cell (wrapped in InspectWrapper by
  // ScalaInterpreterInspections), or in previous ones (wrapped by Ammonite, and printed like
  // `instance.Foo` or `ammonite.$sess.cmd2.Helper.Foo`). This is a heuristic on printed types: a
  // bare `instance.` prefix is only removed before a capitalized type name, so that a user value
  // named `instance` is at worst printed without its prefix in path-dependent types.
  private val WrapperPrefix =
    """\bInspectWrapper\.|\bammonite\.\$sess\.cmd\d+\.(?:Helper\.|instance\.)?|(?<![\w$.])instance\.(?=[A-Z])""".r

  def withoutWrappers(s: String): String =
    WrapperPrefix.replaceAllIn(s, "")

  /** The class, object, or package where sym is defined, unless it's one of the wrappers of the
    * code of the current or previous cells
    */
  def definedIn(sym: Symbol)(using Context): Option[String] =
    if (sym.is(Flags.Package)) None
    else {
      val owner = sym.owner
      // without the wrapper prefixes of the classes defined in cells, and nothing at all if
      // the owner is one of these wrappers
      val name =
        withoutWrappers(owner.fullName.toString.stripSuffix("$") + ".").stripSuffix(".")
      val isWrapper =
        name.isEmpty || name.contains("$sess") || owner.isEmptyPackage || owner.isRoot
      if (owner.exists && owner.isClass && !isWrapper) Some(name)
      else None
    }
}
