package almond.internals

import scala.meta.internal.docstrings._
import scala.meta.internal.docstrings.printers.{ListType, ScalaDocPrinter}

/** Renders scaladoc comments as Markdown
  *
  * Like mtags' `MarkdownGenerator`, except for links: links to other symbols, like `[[walk]]` or
  * `[[os.Path]]`, are rendered as code, `walk` or `os.Path`, rather than as Markdown links whose
  * target isn't a URL, like `[walk](walk)`. Subscripts and superscripts aren't dropped either, and
  * the lines of paragraphs aren't indented.
  */
private[almond] object ScaladocMarkdown extends ScalaDocPrinter {

  protected def blockToText(block: Block, listLevel: Int): String =
    block match {
      case Title(text, level) =>
        s"""${"#" * level} ${inlineToText(text)}"""
      case UnorderedList(blocks) =>
        listBlocksIndent(blocks, ListType.Bullet('-'), listLevel)
      case OrderedList(blocks, _) =>
        listBlocksIndent(blocks, ListType.Bullet('*'), listLevel)
      case Paragraph(text) =>
        // without the indentation of the lines of the comment (mtags keeps the space
        // following the leading '*' of these)
        inlineToText(text).linesIterator.map(_.dropWhile(_ == ' ')).mkString("\n") + "\n"
      case Code(data) =>
        s"```\n$data\n```"
      case HorizontalRule() =>
        "---"
      case _ =>
        ""
    }

  private def isUrl(target: String): Boolean =
    target.contains("://") || target.startsWith("mailto:")

  protected def inlineToText(i: Inline): String =
    i match {
      case Chain(items) =>
        items.map(inlineToText).mkString
      case Summary(text) =>
        inlineToText(text)
      case Monospace(text) =>
        s"`${inlineToText(text)}`"
      case Text(text) =>
        text
      case HtmlTag(data) =>
        data
      case Underline(text) =>
        inlineToText(text)
      case Subscript(text) =>
        // like x,,1,, in the scaladoc of foldLeft
        val str = inlineToText(text)
        if (str.nonEmpty && str.forall(c => c >= '0' && c <= '9'))
          str.map(c => (c - '0' + '\u2080').toChar)
        else
          "_" + str
      case Superscript(text) =>
        "^" + inlineToText(text)
      case Italic(text) =>
        s"*${inlineToText(text)}*"
      case Bold(text) =>
        s"**${inlineToText(text)}**"
      case Link(target, title) if isUrl(target) =>
        s"[${inlineToText(title)}]($target)"
      case Link(_, Monospace(title)) =>
        s"`${inlineToText(title)}`"
      case Link(_, title) =>
        s"`${inlineToText(title)}`"
      case _ =>
        ""
    }

  protected def wrapParam(param: String): String = s"`$param`"
  protected def constructor: String              = "**Constructor:**"
  protected def deprecated: String               = "**Deprecated:**"
  protected def examples: String                 = "**Examples**\n"
  override protected def notesTitle: String      = "**Notes**\n"
  override protected def typeParamsTitle: String = "**Type Parameters**\n"
  override protected def parametersTitle: String = "**Parameters**\n"
  protected def returns: String                  = "**Returns:**"
  override protected def throwsTitle: String     = "**Throws**\n"
  override protected def seeTitle: String        = "**See**\n"

}
