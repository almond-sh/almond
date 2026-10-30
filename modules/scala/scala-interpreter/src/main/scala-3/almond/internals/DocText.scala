package almond.internals

import ammonite.util.Util.newLine

import scala.collection.mutable

/** A doc comment, split into its main text and its block tags (`@param`, …), rendered as plain text
  * in inspections
  */
private[internals] final case class DocText(main: String, tags: List[(String, String)]) {

  /** Substitutes scaladoc variables, like `$coll` */
  def expand(vars: Map[String, String]): DocText = {
    def expand0(s: String, depth: Int): String =
      if (depth > 10) s // recursive definitions
      else {
        val res = DocText.Variable.replaceAllIn(
          s,
          m => java.util.regex.Matcher.quoteReplacement(vars.getOrElse(m.group(1), m.matched))
        )
        if (res == s) s else expand0(res, depth + 1)
      }
    DocText(expand0(main, 0), tags.map((t, c) => (t, expand0(c, 0))))
  }

  /** Renders this doc like
    * {{{
    * Builds a new list by applying a function to all elements of this list.
    *
    * Parameters:
    *   f: the function to apply to each element.
    * }}}
    */
  def render: String = {
    val sections = DocText.sections.flatMap { (tag, title) =>
      val contents = tags.collect { case (`tag`, c) => c }
      if (contents.isEmpty) None
      else {
        val lines =
          if (tag == "example") contents.flatMap(_.linesIterator.map("  " + _))
          else
            contents.map { c =>
              val c0 = c.replaceAll("\\s+", " ").trim
              if (DocText.namedTags(tag)) {
                val (name, desc) = c0.span(!_.isWhitespace)
                s"  $name: ${desc.trim}"
              }
              else "  " + c0
            }
        Some(InspectionOutput.docSection(title, lines))
      }
    }
    (main.trim +: sections).filter(_.nonEmpty).mkString(newLine + newLine)
  }
}

private[internals] object DocText {

  private val Variable  = """\$\{?(\w+)\}?""".r
  private val BlockTag  = """^@(\w+)\s*(.*)$""".r
  private val namedTags = Set("param", "tparam", "throws", "exception")

  // same order and titles as the sections of dotty.tools.dotc.util.ParsedComment
  val sections = List(
    "tparam"     -> "Type Parameters",
    "param"      -> "Parameters",
    "return"     -> "Returns",
    "throws"     -> "Throws",
    "exception"  -> "Throws",
    "see"        -> "See Also",
    "example"    -> "Examples",
    "note"       -> "Note",
    "since"      -> "Since",
    "version"    -> "Version",
    "deprecated" -> "Deprecated"
  )

  // Scaladoc tags that aren't shown
  private val ignoredTags = Set(
    "define",
    "usecase",
    "group",
    "groupname",
    "groupdesc",
    "groupprio",
    "migration",
    "author",
    "documentable",
    "contentDiagram",
    "inheritanceDiagram",
    "hideImplicitConversion",
    "template",
    "shortDescription",
    "constructor",
    "inheritdoc"
  )

  /** Splits the lines of a doc comment (without its `/**`, `*/`, and leading `*`s) into its main
    * text and its block tags
    */
  def split(lines: Seq[String]): DocText = {
    val main = lines.takeWhile(l => !l.trim.startsWith("@"))
    val tags = new mutable.ListBuffer[(String, String)]
    for (line <- lines.drop(main.length))
      line.trim match {
        case BlockTag(tag, rest) => tags += ((tag, rest))
        case _ if tags.nonEmpty  =>
          val (tag, content) = tags.last
          tags(tags.length - 1) = (tag, content + newLine + line)
        case _ =>
      }
    DocText(main.mkString(newLine), tags.toList)
  }

  /** Parses a raw scaladoc comment, like `/** Foo … */`
    *
    * @param inherited
    *   the main text of the doc of an overridden member, for `@inheritdoc`
    */
  def fromScaladoc(raw: String, inherited: => String = ""): DocText = {
    val lines  = scaladocLines(raw)
    val lines0 =
      if (lines.exists(_.contains("@inheritdoc")))
        lines.flatMap { l =>
          if (l.contains("@inheritdoc")) l.replace("@inheritdoc", inherited).linesIterator
          else Iterator(l)
        }
      else lines
    val parsed = split(lines0)
    DocText(
      wiki(parsed.main),
      parsed.tags.filter((t, _) => !ignoredTags(t)).map((t, c) => (t, wiki(c)))
    )
  }

  /** The scaladoc variables defined with `@define` in a raw scaladoc comment */
  def defines(raw: String): Map[String, String] =
    split(scaladocLines(raw)).tags.collect {
      case ("define", content) =>
        val (name, value) = content.trim.span(!_.isWhitespace)
        name -> value.trim.replaceAll("\\s+", " ")
    }.toMap

  // Like "/** First line\n  *  Second line\n  */": the first line doesn't have the same
  // indentation as the others
  private def scaladocLines(raw: String): Seq[String] = {
    val body  = raw.trim.stripPrefix("/**").stripSuffix("*/")
    val lines = body.linesIterator.toVector
    lines.take(1).map(_.trim) ++
      dedent(lines.drop(1).map(_.replaceFirst("""^\s*\*""", "")))
  }

  // Scaladoc wiki syntax to plain text. Links, like [[Response]], are kept as is: they tell
  // that something is a reference to another class or member.
  private def wiki(s: String): String =
    s
      .replaceAll("""\{\{\{""", "```")
      .replaceAll("""\}\}\}""", "```")
      .replace("'''", "")
      .replace("''", "")

  def dedent(lines: Vector[String]): Vector[String] = {
    def indentOf(line: String) = line.takeWhile(_ == ' ').length
    val indent                 = lines.filter(_.trim.nonEmpty).map(indentOf).minOption.getOrElse(0)
    lines.map(l => l.drop(indent min indentOf(l)))
  }
}
