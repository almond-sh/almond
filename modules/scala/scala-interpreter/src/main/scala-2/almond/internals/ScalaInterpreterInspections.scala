package almond.internals

import almond.interpreter._
import almond.interpreter.api.DisplayData
import almond.logger.LoggerContext
import ammonite.runtime.Frame
import ammonite.util.Imports
import ammonite.util.Util.newLine
import scala.meta.dialects
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.scalac.SemanticdbOps

import java.util.concurrent.TimeUnit

import scala.collection.compat._
import scala.tools.nsc.interactive.{Global => Interactive}
import scala.util.Properties
import scala.util.control.NonFatal

final class ScalaInterpreterInspections(
  logCtx: LoggerContext,
  metabrowse: Boolean,
  metabrowseHost: String,
  metabrowsePort: Int,
  scalaVersion: String,
  compilerManager: => ammonite.compiler.CompilerLifecycleManager,
  frames: => List[Frame],
  imports: => Imports
) {

  private val log = logCtx(getClass)

  // FIXME In 2.13 and 3, the actual dialect might be one or the other,
  // when reading JARs from the other version via TASTy compatibility
  private val dialect =
    if (scalaVersion.startsWith("3."))
      dialects.Scala3
    else if (scalaVersion.startsWith("2.13."))
      dialects.Scala213
    else
      dialects.Scala212

  // None if mtags, that SourceIndex relies on, can't be loaded, like on JVMs older than the one
  // it targets (Java 17 for mtags 1.6). Inspections then only give types, without docs.
  private lazy val sourceIndexOpt: Option[SourceIndex] =
    try Some(new SourceIndex(dialect, () => SourcePath.base(frames, log), log))
    catch {
      case e: LinkageError =>
        log.warn(
          "Inspections won't show docs, as the library reading them (mtags) can't be loaded " +
            s"on Java ${sys.props.getOrElse("java.version", "[unknown]")}",
          e
        )
        None
    }

  /** Schedules the indexing of the source JARs of the session that haven't been indexed yet, in the
    * background
    *
    * Called once the interpreter is initialized, and after each cell, so that inspections don't
    * have to wait for indexing, and that the docs of the dependencies added by cells are found.
    */
  def updateSourceIndex(): Unit =
    try
      for (sourceIndex <- sourceIndexOpt)
        sourceIndex.update(SourcePath.session(frames, log))
    catch {
      case NonFatal(e) =>
        log.warn("Error looking for the source JARs of the session", e)
    }

  // How long inspections wait for source JARs being indexed, before returning results without
  // the docs of the JARs that aren't indexed yet
  private def maxIndexWaitSeconds = 3L

  // the source index, once the source JARs being indexed are (or after maxIndexWaitSeconds)
  private def awaitSourceIndex(): Option[SourceIndex] = {
    updateSourceIndex()
    for (sourceIndex <- sourceIndexOpt) yield {
      sourceIndex.await(maxIndexWaitSeconds, TimeUnit.SECONDS)
      sourceIndex
    }
  }

  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] = {
    val pressy = compilerManager.pressy.compiler

    class AlmondSemanticDbOps(val global: pressy.type) extends SemanticdbOps
    val semanticdbOps = new AlmondSemanticDbOps(pressy)
    import semanticdbOps._

    val prefix  = imports.toString() + newLine + "object InspectWrapper{" + newLine
    val suffix  = newLine + "}"
    val allCode = prefix + code + suffix
    val index   = prefix.length + pos

    val currentFile = new scala.reflect.internal.util.BatchSourceFile(
      ammonite.compiler.Compiler.makeFile(allCode.getBytes, name = "Current.sc"),
      allCode
    )

    val r = new scala.tools.nsc.interactive.Response[Unit]
    pressy.askReload(List(currentFile), r)
    r.get.swap match {
      case Left(e) =>
        log.warn(
          s"Error loading '${code.take(pos)}|${code.drop(pos)}' into presentation compiler",
          e
        )
        None
      case Right(()) =>
        val r0 = new scala.tools.nsc.interactive.Response[pressy.Tree]
        pressy.askTypeAt(
          new scala.reflect.internal.util.OffsetPosition(currentFile, index),
          r0
        )
        val treeOrError = r0.get.swap.flatMap { tree =>
          ScalaInterpreterInspections.inspectedTree(pressy)(tree).get.swap
        }
        treeOrError match {
          case Left(e) =>
            log.debug(
              s"Getting type info for '${code.take(pos)}|${code.drop(pos)}' via presentation compiler",
              e
            )
            None
          case Right(None) =>
            log.debug(s"Erroneous tree for '${code.take(pos)}|${code.drop(pos)}'")
            None
          case Right(Some(tree)) =>
            val typeStr = ScalaInterpreterInspections.typeOfTree(pressy)(tree)
              .get
              .fold(
                identity,
                { e =>
                  log.warn("Error getting type string", e)
                  None
                }
              )
              // `tree.toString` used to be the fallback here, but printing a tree calls
              // symName -> isErroneous -> Symbol.info, which is another off-PC-thread
              // symbol read and trips assertCorrectThread. typeOfTree now produces this
              // fallback from inside its own askForResponse block instead.
              .getOrElse("<unknown>")
            // like `Point` rather than `ammonite.$sess.cmd3.instance.Point`, for the types
            // defined in cells
            val typeStr0 = CellWrappers.withoutWrappers(typeStr)

            val docstringsOpt = {
              val symbol = tree.symbol
              if (symbol == null)
                None
              else {
                // Both `.toSemantic` and `allOverriddenSymbols` read symbol infos, which
                // the presentation compiler only allows from its own thread (calling them
                // from another thread trips Global.assertCorrectThread, whose AssertionError
                // is not NonFatal, and terminates the kernel). So all the symbols needed to
                // look for docs are computed here, on the PC thread.
                val symbolInfo = pressy.askForResponse { () =>
                  ScalaInterpreterInspections.docSymbols(pressy)(tree)
                }.get.swap.toOption

                val (candidates, siteClasses) = symbolInfo.getOrElse((Nil, Nil))
                log.debug(
                  s"Symbols for '${code.take(pos)}|${code.drop(pos)}' are $candidates " +
                    s"(site: ${siteClasses.headOption.getOrElse("none")})"
                )

                lazy val sourceIndexOpt0 = awaitSourceIndex()
                def documentation(sym: String): Option[String] =
                  try sourceIndexOpt0.flatMap(_.documentation(sym, siteClasses))
                  catch {
                    // a failed docstring lookup should cost an inspection, not the whole kernel
                    case e: Throwable =>
                      log.warn(s"Ignoring exception when trying to get scaladoc of $sym", e)
                      None
                  }

                // the docs of the symbol, or of the first symbol it overrides that has some
                val documentationOpt =
                  candidates.iterator.flatMap(documentation(_).iterator).find(_.nonEmpty)

                documentationOpt.map { docstring =>
                  if (Properties.isWin)
                    docstring.linesIterator
                      .zip(docstring.linesWithSeparators)
                      .map {
                        case (line, lineWithSep) =>
                          val hasSeparator = line.length < lineWithSep.length
                          if (hasSeparator) line + System.lineSeparator()
                          else lineWithSep
                      }
                      .mkString
                  else
                    docstring
                }
              }
            }

            log.debug(s"Docstring for '${code.take(pos)}|${code.drop(pos)}' is $docstringsOpt")

            import scalatags.Text.all._

            val typeHtml0      = pre(typeStr0)
            val typeHtml: Frag = typeHtml0

            val wholeHtml = div(
              typeHtml,
              docstringsOpt.fold[Frag](Seq.empty[Frag])(pre(_))
            )

            val wholeText = typeStr0 + docstringsOpt.fold("")(newLine + newLine + _)

            // A text/plain alternative alongside the HTML: the VS Code Jupyter PowerToys
            // Contextual Help panel drops any inspect_request reply whose data has no
            // text/plain key, and reports it to the user as "No response from kernel".
            val res = Inspection.fromDisplayData(
              DisplayData.html(wholeHtml.toString)
                .add(DisplayData.ContentType.text, wholeText)
            )

            Some(res)
        }
    }
  }

  def shutdown(): Unit =
    sourceIndexOpt.foreach(_.shutdown())
}

object ScalaInterpreterInspections {

  // Picks the tree to inspect out of the one askTypeAt returned, on the PC thread, as
  // checking for errors reads types. When the cursor sits right after a '.', like in
  // "1.|", the parser yields a selection of an erroneous name, which types as <error>.
  // We inspect its qualifier instead, like IPython does in such cases. None is returned
  // if the tree is still erroneous, so that we don't report "<error>" as a type.
  private def inspectedTree(c: Interactive)(t: c.Tree): c.Response[Option[c.Tree]] =
    c.askForResponse { () =>
      import c._

      def isErroneous(tree: Tree): Boolean =
        tree.isErroneous || (tree.symbol ne null) && tree.symbol.isError

      val tree = t match {
        case s: Select if isErroneous(s) && !isErroneous(s.qualifier) => s.qualifier
        case other                                                    => other
      }

      if (isErroneous(tree)) None
      else Some(tree)
    }

  /** The semanticdb symbols to look for the docs of the symbol of a tree, and the ones of the
    * classes whose scaladoc variables are substituted in these docs. To be called on the PC thread.
    *
    * @return
    *   the symbol, then the symbols it overrides, and the base classes of its site: the class it's
    *   selected from (like `List` in `List(1, 2).map`), or its owner
    */
  private def docSymbols(c: Interactive)(tree: c.Tree): (List[String], List[String]) = {
    import c._
    class Ops(val global: c.type) extends SemanticdbOps
    val ops = new Ops(c)
    import ops._

    val symbol = tree.symbol
    val sym0 =
      if (!symbol.isJava && symbol.isPrimaryConstructor) symbol.owner
      else symbol

    // For objects (like Option, or cats.Monoid, an alias of cats.kernel.Monoid), the docs of
    // their companion class too, as these often have none themselves
    val moduleClass =
      if (sym0.isModule) sym0.moduleClass
      else if (sym0.isModuleClass) sym0
      else
        Option(tree.tpe)
          .map(_.widen.typeSymbol)
          .filter(_.isModuleClass)
          .getOrElse(NoSymbol)
    val companions =
      if (moduleClass == NoSymbol) Nil
      else {
        val module = moduleClass.sourceModule
        List(module, module.companionClass)
      }

    val site = tree match {
      case s: Select if (s.qualifier.tpe ne null) && (s.qualifier.tpe ne NoType) =>
        s.qualifier.tpe.widen.typeSymbol
      case _ => sym0.owner
    }
    val siteClasses = if (site.isClass) site.baseClasses else Nil

    val candidates = (sym0 :: companions ::: sym0.allOverriddenSymbols)
      .filter(_ != NoSymbol)
      .distinct
      .map(_.toSemantic)
      .filter(s => !s.isEmpty)
    (candidates, siteClasses.map(_.toSemantic).filter(s => !s.isEmpty))
  }

  // from https://github.com/scalameta/metals/blob/cec8b98cba23110d5b2919d9879c78d3b0146ab2/metaserver/src/main/scala/scala/meta/languageserver/providers/HoverProvider.scala#L34-L51
  // (via https://github.com/almond-sh/almond/pull/235#discussion_r222696661)
  private def typeOfTree(c: Interactive)(t: c.Tree): c.Response[Option[String]] =
    c.askForResponse { () =>
      import c._

      val stringOrTree = t match {
        case t: DefDef                  => Right(t.symbol.asMethod.info.toLongString)
        case t: ValDef if t.tpt != null => Left(t.tpt)
        case t: ValDef if t.rhs != null => Left(t.rhs)
        case x                          => Left(x)
      }

      stringOrTree match {
        case Right(string) => Some(string)
        case Left(null)    => None
        // askTypeAt can hand back a tree that has not been typed yet while a background
        // compile is in flight, so `tpe` needs a null check as well as the NoType one.
        case Left(tree) if (tree.tpe ne null) && (tree.tpe ne NoType) =>
          Some(tree.tpe.widen.toString)
        // last resort: print the tree. This has to happen here, on the PC thread,
        // because printing reads symbol infos (symName -> isErroneous -> Symbol.info).
        case Left(tree) => Some(tree.toString)
        case _          => None
      }
    }

}
