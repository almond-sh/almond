package almond.internals

import java.nio.file.{Path, Paths}

import almond.interpreter._
import almond.interpreter.api.DisplayData
import almond.logger.{Logger, LoggerContext}
import ammonite.runtime.Frame
import ammonite.util.Util.newLine
import scala.meta.dialects
import scala.meta.internal.metals.{Docstrings, EmptyReportContext}
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.{IndexingExceptions, OnDemandSymbolIndex}
import scala.meta.internal.semanticdb.scalac.SemanticdbOps
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolDocumentation

import java.util.Optional

import scala.collection.compat._
import scala.jdk.CollectionConverters._
import scala.tools.nsc.interactive.{Global => Interactive}

final class ScalaInterpreterInspections(
  logCtx: LoggerContext,
  metabrowse: Boolean,
  metabrowseHost: String,
  metabrowsePort: Int,
  scalaVersion: String,
  compilerManager: => ammonite.compiler.CompilerLifecycleManager,
  frames: => List[Frame]
) {

  private val metabrowseServer = new AlmondMetabrowseServer(
    logCtx,
    metabrowse,
    metabrowseHost,
    metabrowsePort,
    scalaVersion,
    frames
  )

  private val log = logCtx(getClass)

  private lazy val docs: Docstrings = {

    val sourcePaths = AlmondMetabrowseServer.sourcePath(frames, log).sources

    val symbolIndex = {
      // FIXME In 2.13 and 3, the actual dialect might be one or the other,
      // when reading JARs from the other version via TASTy compatibility
      val dialect =
        if (scalaVersion.startsWith("3."))
          dialects.Scala3
        else if (scalaVersion.startsWith("2.13."))
          dialects.Scala213
        else
          dialects.Scala212
      val index = OnDemandSymbolIndex.empty()(EmptyReportContext)
      for (p <- sourcePaths)
        try index.addSourceJar(AbsolutePath(p), dialect)
        catch {
          case e: IndexingExceptions.PathIndexingException =>
            log.warn(s"Ignoring exception while indexing $p", e)
          case e: IndexingExceptions.InvalidJarException =>
            log.warn(s"Ignoring exception while indexing $p", e)
          case e: IndexingExceptions.InvalidSymbolException =>
            log.warn(s"Ignoring exception while indexing $p", e)
        }
      index
    }

    new Docstrings(symbolIndex)
  }

  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] = {
    val pressy = compilerManager.pressy.compiler

    class AlmondSemanticDbOps(val global: pressy.type) extends SemanticdbOps
    val semanticdbOps = new AlmondSemanticDbOps(pressy)
    import semanticdbOps._

    val prefix  = frames.head.imports.toString() + newLine + "object InspectWrapper{" + newLine
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
        r0.get.swap match {
          case Left(e) =>
            log.debug(
              s"Getting type info for '${code.take(pos)}|${code.drop(pos)}' via presentation compiler",
              e
            )
            None
          case Right(tree) =>
            val urlOpt = metabrowseServer.urlFor(pressy)(code, pos, detailLevel, tree)

            val typeStr = ScalaInterpreterInspections.typeOfTree(pressy)(tree)
              .get
              .fold(
                identity,
                { e =>
                  log.warn("Error getting type string", e)
                  None
                }
              )
              .getOrElse(tree.toString)

            val docstringsOpt = {
              val symbol = tree.symbol
              if (symbol == null)
                None
              else {
                val sym = {
                  if (!symbol.isJava && symbol.isPrimaryConstructor) symbol.owner
                  else symbol
                }.toSemantic
                log.debug(s"Symbol for '${code.take(pos)}|${code.drop(pos)}' is $sym")

                val documentation =
                  try
                    docs.documentation(
                      sym,
                      () => symbol.allOverriddenSymbols.map(_.toSemantic).toList.asJava,
                      scala.meta.pc.ContentType.MARKDOWN
                    )
                  catch {
                    case e: IndexingExceptions.InvalidSymbolException =>
                      log.warn(s"Ignoring exception when trying to get scaladoc of $sym", e)
                      Optional.empty[SymbolDocumentation]()
                  }

                if (documentation.isPresent) Some(documentation.get().docstring)
                else None
              }
            }

            log.debug(s"Docstring for '${code.take(pos)}|${code.drop(pos)}' is $docstringsOpt")

            import scalatags.Text.all._

            val typeHtml0 = pre(typeStr)
            val typeHtml: Frag = urlOpt.fold(typeHtml0) {
              case (url, metabrowseWindowId) =>
                a(href := url, target := metabrowseWindowId, typeHtml0)
            }

            val wholeHtml = div(
              typeHtml,
              docstringsOpt.fold[Frag](Seq.empty[Frag])(pre(_))
            )

            val res = Inspection.fromDisplayData(
              DisplayData.html(wholeHtml.toString)
            )

            Some(res)
        }
    }
  }

  def shutdown(): Unit =
    metabrowseServer.shutdown()

}

object ScalaInterpreterInspections {

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
        case Right(string)                    => Some(string)
        case Left(null)                       => None
        case Left(tree) if tree.tpe ne NoType => Some(tree.tpe.widen.toString)
        case _                                => None
      }
    }

}
