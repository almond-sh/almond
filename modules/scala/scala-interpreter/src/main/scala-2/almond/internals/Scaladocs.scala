package almond.internals

import scala.collection.mutable
import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.docstrings.{Inline, Link, ScaladocParser}
import scala.meta.internal.metals.ScaladocIndexer
import scala.meta.internal.mtags.{GlobalSymbolIndex, ScalaMtags, Symbol}
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.{Language, SymbolInformation, SymbolOccurrence}
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath
import scala.meta.trees.Origin

/** The scaladoc of Scala symbols, read from the source JARs of an index, rendered as Markdown
  *
  * Like mtags' `Docstrings`, but:
  *   - scaladoc variables (`$coll`, …) are substituted with the values defined with `@define` in
  *     the docs of the class the member is selected from (its "site", like `List` in
  *     `List(1, 2).map`) and of its base classes, and of the classes enclosing the member, like
  *     scaladoc does, rather than only with those defined earlier in the same source file,
  *   - the values of these variables can refer to other variables, like `@define ccoll $coll`,
  *   - links to other symbols, like `[[walk]]`, are rendered as code, rather than as Markdown links
  *     whose targets aren't URLs.
  */
private[almond] final class Scaladocs(index: GlobalSymbolIndex) {

  import Scaladocs._

  // raw doc comments of the definitions of the last source files read, by semanticdb symbol
  private val files    = new mutable.LinkedHashMap[AbsolutePath, Map[String, String]]
  private def maxFiles = 32

  private def fileDocs(path: AbsolutePath, dialect: Dialect): Map[String, String] =
    files.synchronized {
      val docs = files.remove(path).getOrElse {
        val indexer = new RawDocIndexer(path.toInput, dialect)
        indexer.indexRoot()
        indexer.docs.toMap
      }
      // most recently used last
      files(path) = docs
      if (files.size > maxFiles)
        files.remove(files.head._1)
      docs
    }

  /** Whether a symbol is defined in a Java source file of the index */
  def isJava(symbol: String): Boolean =
    index.definition(Symbol(symbol)).exists(_.path.toLanguage == Language.JAVA)

  /** The raw doc comment of a symbol defined in Scala sources, like `/** Foo … */` */
  def rawDoc(symbol: String): Option[String] =
    index.definition(Symbol(symbol))
      .filter(_.path.toLanguage == Language.SCALA)
      .flatMap { defn =>
        val docs = fileDocs(defn.path, defn.dialect)
        docs.get(symbol).orElse(docs.get(defn.definitionSymbol.value))
      }
      .filter(_.nonEmpty)

  /** The rendered doc of a symbol
    *
    * @param symbol
    *   the semanticdb symbol whose doc is rendered
    * @param siteClasses
    *   the semanticdb symbols of the class `symbol` is selected from and of its base classes, most
    *   specific first, whose `@define` variables are substituted in the doc
    */
  def documentation(symbol: String, siteClasses: Seq[String]): Option[String] =
    rawDoc(symbol).map { raw =>
      // like scaladoc, the variables of the site take precedence over those of the class
      // where symbol is defined, so that the doc of a member inherited by List says "list"
      val owners = Iterator
        .iterate(symbol.owner)(_.owner)
        .takeWhile(s => s.nonEmpty && !s.isNone && !s.isRootPackage && !s.isEmptyPackage)
        .filter(!_.isPackage)
        .toList
      val defines = mutable.LinkedHashMap.empty[String, String]
      def addDefines(raw: String): Unit =
        for ((k, v) <- ScaladocParser.extractDefines(raw) if !defines.contains(k))
          defines(k) = v
      addDefines(raw)
      for (cls <- (siteClasses ++ owners).distinct; clsRaw <- rawDoc(cls))
        addDefines(clsRaw)
      val comment = ScaladocParser.parseComment(raw, expandDefines(defines))
      ScaladocMarkdown.toText(comment, raw)
    }.filter(_.nonEmpty)
}

private[almond] object Scaladocs {

  private val Variable = "\\$([a-zA-Z]+)".r

  /** The values of `defines`, with the variables they refer to substituted */
  def expandDefines(defines: collection.Map[String, String]): Map[String, String] = {
    def expand(value: String, depth: Int): String =
      if (depth > 10) value // recursive definitions
      else {
        val res = Variable.replaceAllIn(
          value,
          m =>
            java.util.regex.Matcher.quoteReplacement(defines.getOrElse(m.group(1), m.matched))
        )
        if (res == value) value else expand(res, depth + 1)
      }
    defines.iterator.map { case (k, v) => k -> expand(v, 0) }.toMap
  }

  // Records the raw doc comment of the definitions of a source file
  private final class RawDocIndexer(input: Input.VirtualFile, dialect: Dialect)
      extends ScalaMtags(input, dialect) {
    val docs = new mutable.HashMap[String, String]
    override def visitOccurrence(
      occ: SymbolOccurrence,
      sinfo: SymbolInformation,
      owner: String
    ): Unit = {
      val doc = currentTree.origin match {
        case Origin.Parsed(_, start, _) =>
          ScaladocIndexer.findLeadingDocstring(source.tokens, start - 1).getOrElse("")
        case _ => ""
      }
      docs(occ.symbol) = doc
    }
  }
}
