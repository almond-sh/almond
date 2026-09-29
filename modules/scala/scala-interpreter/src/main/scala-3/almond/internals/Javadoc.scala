package almond.internals

import ammonite.util.Util.newLine

import scala.collection.mutable

/** Javadoc comments, whose text is HTML, with inline tags like `{@code …}` */
private[internals] object Javadoc {

  /** Parses the text of a Javadoc comment (without its `/**`, `*/`, and leading `*`s)
    *
    * @param inherited
    *   the main text of the doc of an overridden method, for `{@inheritDoc}`
    */
  def parse(raw: String, inherited: => String = ""): DocText = {
    val raw0 =
      if (raw.contains("{@inheritDoc}")) raw.replace("{@inheritDoc}", inherited) else raw
    val parsed = DocText.split(raw0.linesIterator.toVector)
    val tags = parsed.tags.map {
      // type parameters are documented with @param <T> in Javadoc
      case ("param", c) if c.trim.startsWith("<") =>
        ("tparam", c.trim.stripPrefix("<").replaceFirst(">", ""))
      case other => other
    }
    DocText(
      html(parsed.main),
      tags.filter((t, _) => DocText.sections.exists(_._1 == t)).map((t, c) => (t, html(c)))
    )
  }

  private def html(s: String): String = {
    def inlineTags(s: String) = s
      .replaceAll("""\{@(?:code|literal)\s+([^}]*)\}""", "`$1`")
      .replaceAll("""\{@(?:link|linkplain)\s+(?:\S+)\s+([^}]+)\}""", "$1")
      .replaceAll("""\{@(?:link|linkplain)\s+([^}\s]+)\s*\}""", "`$1`")
      .replaceAll("""\{@\w+\s*([^}]*)\}""", "$1")
    // keep the line breaks of <pre> blocks, indented, and reflow the rest
    val parts = s.split("(?i)</?pre>", -1).toList
    parts.zipWithIndex
      .map { (part, idx) =>
        if (idx % 2 == 1) {
          val code =
            if (part.trim.startsWith("{@code"))
              part.replaceFirst("""^\s*\{@code""", "").replaceFirst("""\}\s*$""", "")
            else part
          val lines = code.linesIterator.toVector
            .dropWhile(_.trim.isEmpty)
            .reverse
            .dropWhile(_.trim.isEmpty)
            .reverse
          DocText.dedent(lines)
            .map(l => "    " + entities(l.replaceAll("<[^>]+>", "")))
            .mkString(newLine)
        }
        else {
          val text = inlineTags(part)
            .replaceAll("(?i)<p\\s*/?>|</p>|<br\\s*/?>|</?[uo]l>|</?blockquote>", "\n\n")
            .replaceAll("(?i)<li>", "\n\n- ")
            .replaceAll("<[^>]+>", "")
          text
            .split("\n\\s*\n")
            .map(_.trim.replaceAll("\\s+", " "))
            .filter(_.nonEmpty)
            .map(entities)
            .mkString(newLine + newLine)
        }
      }
      .filter(_.trim.nonEmpty)
      .mkString(newLine + newLine)
  }

  private def entities(s: String): String =
    s.replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#64;", "@")
      .replace("&nbsp;", " ")
      .replace("&amp;", "&")
}

/** A member of a Java source file, with its Javadoc comment
  *
  * @param classes
  *   the classes enclosing it, outermost first
  * @param name
  *   its name (`<init>` for constructors)
  * @param params
  *   the simple names of the types of its parameters, if it's a method
  */
private[internals] final case class JavaMember(
  classes: List[String],
  name: String,
  params: List[String],
  doc: String,
  paramNames: List[String] = Nil
)

/** Extracts the Javadoc comments of a Java source file, along with what they document.
  *
  * The javac tree API can't be used here (this module targets Java 8, which doesn't expose it, and
  * it's not there at all when running on a JRE). So this tokenizes the source, tracks the nesting
  * of classes via braces, and looks at the tokens following each doc comment, up to the next `{`,
  * `;` or `=`: the declaration of what the comment documents.
  */
private[internals] object JavaScanner {

  private sealed abstract class Token           extends Product with Serializable
  private final case class Ident(value: String) extends Token
  private final case class Sym(value: Char)     extends Token
  private final case class Doc(value: String)   extends Token

  private val classKeywords = Set("class", "interface", "enum", "record")
  private val modifiers = Set(
    "public",
    "protected",
    "private",
    "static",
    "final",
    "abstract",
    "native",
    "synchronized",
    "transient",
    "volatile",
    "strictfp",
    "default",
    "sealed",
    "non"
  )

  private def tokens(src: String): Vector[Token] = {
    val b   = Vector.newBuilder[Token]
    var i   = 0
    val len = src.length
    def skipQuoted(quote: Char): Unit = {
      i += 1
      while (i < len && src(i) != quote) {
        if (src(i) == '\\') i += 1
        i += 1
      }
      i += 1
    }
    while (i < len) {
      val c = src(i)
      if (c.isWhitespace) i += 1
      else if (src.startsWith("/**", i) && !src.startsWith("/**/", i)) {
        val end  = src.indexOf("*/", i + 3)
        val stop = if (end < 0) len else end
        b += Doc(src.substring(i + 3, stop))
        i = stop + 2
      }
      else if (src.startsWith("/*", i)) {
        val end = src.indexOf("*/", i + 2)
        i = if (end < 0) len else end + 2
      }
      else if (src.startsWith("//", i)) {
        val end = src.indexOf('\n', i)
        i = if (end < 0) len else end + 1
      }
      else if (src.startsWith("\"\"\"", i)) {
        val end = src.indexOf("\"\"\"", i + 3)
        i = if (end < 0) len else end + 3
        b += Sym('"')
      }
      else if (c == '"' || c == '\'') {
        skipQuoted(c)
        b += Sym(c)
      }
      else if (Character.isJavaIdentifierStart(c) || c.isDigit) {
        val start = i
        while (i < len && Character.isJavaIdentifierPart(src(i))) i += 1
        b += Ident(src.substring(start, i))
      }
      else {
        b += Sym(c)
        i += 1
      }
    }
    b.result()
  }

  // Javadoc text, without the leading '*' of each line
  private def docText(raw: String): String =
    raw.linesIterator
      .map(_.replaceFirst("""^\s*\*""", ""))
      .mkString("\n")
      .trim

  // Drops annotations, like @Deprecated or @SuppressWarnings("x")
  private def dropAnnotations(toks: Vector[Token]): Vector[Token] = {
    val b = Vector.newBuilder[Token]
    var i = 0
    while (i < toks.length)
      toks(i) match {
        case Sym('@') if i + 1 < toks.length && toks(i + 1) == Ident("interface") =>
          b += toks(i + 1)
          i += 2
        case Sym('@') =>
          i += 2
          while (i + 1 < toks.length && toks(i) == Sym('.')) i += 2
          if (i < toks.length && toks(i) == Sym('(')) {
            var depth = 0
            var done  = false
            while (i < toks.length && !done) {
              toks(i) match {
                case Sym('(') => depth += 1
                case Sym(')') =>
                  depth -= 1
                  done = depth == 0
                case _ =>
              }
              i += 1
            }
          }
        case other =>
          b += other
          i += 1
      }
    b.result()
  }

  private def show(toks: Seq[Token]): String =
    toks.map {
      case Ident(v) => v
      case Sym(c)   => c.toString
      case Doc(_)   => ""
    }.mkString

  // Splits on commas that aren't within <…> or (…)
  private def splitParams(toks: Vector[Token]): List[Vector[Token]] = {
    val res   = new mutable.ListBuffer[Vector[Token]]
    var cur   = Vector.newBuilder[Token]
    var depth = 0
    for (t <- toks)
      t match {
        case Sym(',') if depth == 0 =>
          res += cur.result()
          cur = Vector.newBuilder[Token]
        case Sym('<' | '(') =>
          depth += 1
          cur += t
        case Sym('>' | ')') =>
          depth -= 1
          cur += t
        case _ => cur += t
      }
    val last = cur.result()
    if (last.nonEmpty) res += last
    res.toList
  }

  private def member(header: Vector[Token], classes: List[String], doc: String) = {
    val toks = dropAnnotations(header)
    val classKeywordIdx = toks.indexWhere {
      case Ident(v) => classKeywords(v)
      case _        => false
    }
    val parenIdx = toks.indexOf(Sym('('))
    if (classKeywordIdx >= 0)
      toks.lift(classKeywordIdx + 1).collect {
        case Ident(name) => JavaMember(classes, name, Nil, doc)
      }
    else if (parenIdx > 0)
      toks(parenIdx - 1) match {
        case Ident(name) =>
          var depth = 0
          val close = toks.indexWhere(
            {
              case Sym('(') => depth += 1; false
              case Sym(')') => depth -= 1; depth == 0
              case _        => false
            },
            parenIdx
          )
          val paramToks = toks.slice(parenIdx + 1, if (close < 0) toks.length else close)
          val params = splitParams(paramToks).map { p =>
            val p0 = p.filter(_ != Ident("final"))
            // the last identifier is the parameter name
            p0.lastIndexWhere(_.isInstanceOf[Ident]) match {
              case -1 => (JavaTypeNames.simple(show(p0)), "")
              case idx =>
                (JavaTypeNames.simple(show(p0.take(idx))), show(p0.slice(idx, idx + 1)))
            }
          }
          val name0 = if (classes.lastOption.contains(name)) "<init>" else name
          Some(JavaMember(classes, name0, params.map(_._1), doc, params.map(_._2)))
        case _ => None
      }
    else
      toks.reverseIterator.collectFirst { case Ident(name) if !modifiers(name) => name }
        .map(JavaMember(classes, _, Nil, doc))
  }

  def members(src: String): Seq[JavaMember] = {
    val toks    = tokens(src)
    val res     = new mutable.ListBuffer[JavaMember]
    val classes = new mutable.Stack[(String, Int)]
    var pending = Option.empty[String]
    var depth   = 0
    var i       = 0
    while (i < toks.length) {
      toks(i) match {
        case Ident(kw) if classKeywords(kw) && !(i > 0 && toks(i - 1) == Sym('.')) =>
          toks.lift(i + 1).collect { case Ident(name) => name }.foreach(n => pending = Some(n))
        case Sym('{') =>
          depth += 1
          for (name <- pending) classes.push((name, depth))
          pending = None
        case Sym('}') =>
          if (classes.headOption.exists(_._2 == depth)) classes.pop()
          depth -= 1
        case Sym(';') =>
          pending = None
        case Doc(raw) =>
          // only the declarations of classes and of their members
          val inClassBody = classes.headOption.forall(_._2 == depth)
          if (inClassBody) {
            val header = toks
              .drop(i + 1)
              .takeWhile {
                case Sym('{' | ';' | '=') => false
                case Doc(_)               => false
                case _                    => true
              }
            val names = classes.toList.reverse.map(_._1)
            member(header, names, docText(raw)).foreach(res += _)
          }
        case _ =>
      }
      i += 1
    }
    res.toList
  }
}

private[internals] object JavaTypeNames {

  /** Like "String", "int[]", "List": no package, no type arguments, no annotations */
  def simple(name: String): String = {
    val noArgs = {
      val b     = new StringBuilder
      var depth = 0
      for (c <- name)
        if (c == '<') depth += 1
        else if (c == '>') depth -= 1
        else if (depth == 0) b += c
      b.result()
    }
    val normalized = noArgs
      .replaceAll("""@[\w.]+(\([^)]*\))?""", "")
      .replace("...", "[]")
      .replaceAll("\\s+", "")
    val base = normalized.takeWhile(_ != '[')
    base.drop(base.lastIndexOf('.') + 1) + "[]" * normalized.count(_ == '[')
  }
}
