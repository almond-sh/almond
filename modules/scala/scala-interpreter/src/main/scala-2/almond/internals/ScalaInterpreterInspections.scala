package almond.internals

import almond.interpreter._
import almond.interpreter.api.DisplayData
import almond.logger.LoggerContext
import ammonite.runtime.Frame
import ammonite.util.Imports
import ammonite.util.Util.newLine
import scala.meta.dialects
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.scalac.SemanticdbOps
import scala.meta.pc.SymbolDocumentation

import java.util.Optional
import java.util.concurrent.TimeUnit

import scala.collection.compat._
import scala.jdk.CollectionConverters._
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

  private val sourceIndex = new SourceIndex(dialect, () => SourcePath.base(frames, log), log)

  /** Schedules the indexing of the source JARs of the session that haven't been indexed yet, in the
    * background
    *
    * Called once the interpreter is initialized, and after each cell, so that inspections don't
    * have to wait for indexing, and that the docs of the dependencies added by cells are found.
    */
  def updateSourceIndex(): Unit =
    try sourceIndex.update(SourcePath.session(frames, log))
    catch {
      case NonFatal(e) =>
        log.warn("Error looking for the source JARs of the session", e)
    }

  // How long inspections wait for source JARs being indexed, before returning results without
  // the docs of the JARs that aren't indexed yet
  private def maxIndexWaitSeconds = 3L

  private def docs: Docstrings = {
    updateSourceIndex()
    sourceIndex.await(maxIndexWaitSeconds, TimeUnit.SECONDS)
    sourceIndex.docs
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

            val docstringsOpt = {
              val symbol = tree.symbol
              if (symbol == null)
                None
              else {
                // Both `.toSemantic` and `allOverriddenSymbols` read symbol infos, which
                // the presentation compiler only allows from its own thread. Computing
                // them here, and in particular handing Docstrings a thunk that it
                // invokes later from parentDocumentation, trips
                // Global.assertCorrectThread; that AssertionError is not NonFatal, so it
                // terminates the kernel. Compute both on the PC thread instead, and pass
                // a thunk that only returns the finished value.
                val symbolInfo = pressy.askForResponse { () =>
                  val s = {
                    if (!symbol.isJava && symbol.isPrimaryConstructor) symbol.owner
                    else symbol
                  }.toSemantic
                  (s, symbol.allOverriddenSymbols.map(_.toSemantic).toList.asJava)
                }.get.swap.toOption

                val (sym, parents) = symbolInfo.getOrElse(
                  ("", java.util.Collections.emptyList[String]())
                )
                log.debug(s"Symbol for '${code.take(pos)}|${code.drop(pos)}' is $sym")

                lazy val docs0 = docs
                val documentation =
                  if (sym.isEmpty)
                    Optional.empty[SymbolDocumentation]()
                  else
                    try
                      docs0.documentation(
                        sym,
                        () => parents,
                        scala.meta.pc.ContentType.MARKDOWN
                      )
                    catch {
                      // widened from InvalidSymbolException: a failed docstring lookup
                      // should cost an inspection, not the whole kernel
                      case e: Throwable =>
                        log.warn(s"Ignoring exception when trying to get scaladoc of $sym", e)
                        Optional.empty[SymbolDocumentation]()
                    }

                if (documentation.isPresent && documentation.get().docstring.nonEmpty) {
                  val docstring = documentation.get().docstring
                  val finalDocstring =
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
                  Some(finalDocstring)
                }
                else
                  None
              }
            }

            log.debug(s"Docstring for '${code.take(pos)}|${code.drop(pos)}' is $docstringsOpt")

            import scalatags.Text.all._

            val typeHtml0      = pre(typeStr)
            val typeHtml: Frag = typeHtml0

            val wholeHtml = div(
              typeHtml,
              docstringsOpt.fold[Frag](Seq.empty[Frag])(pre(_))
            )

            val wholeText = typeStr + docstringsOpt.fold("")(newLine + newLine + _)

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
    sourceIndex.shutdown()
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
