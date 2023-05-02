package almond.internals

import java.io.File
import java.net.URI
import java.nio.file.{Path, Paths}

import almond.channels.ConnectionParameters
import almond.interpreter._
import almond.interpreter.api.DisplayData
import almond.logger.{Logger, LoggerContext}
import ammonite.runtime.Frame
import ammonite.util.Ref
import ammonite.util.Util.newLine
import scala.meta.dialects
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.semanticdb.scalac.SemanticdbOps
import scala.meta.io.AbsolutePath

import scala.collection.JavaConverters._
import scala.collection.compat._
import scala.tools.nsc.Global
import scala.tools.nsc.interactive.{Global => Interactive}
import scala.util.Random

final class ScalaInterpreterInspections(
  logCtx: LoggerContext,
  compilerManager: => ammonite.compiler.CompilerLifecycleManager,
  frames: => List[Frame]
) {

  private val log = logCtx(getClass)

  private lazy val docs: Docstrings = {

    val baseSourcePath = ScalaInterpreterInspections.baseSourcePath(
      frames
        .last
        .classloader
        .getParent,
      log
    )

    val sourcePaths: List[Path] = {

      val sources = frames
        .flatMap(_.classpath)
        .collect {
          // FIXME We're ignoring jars-in-jars of standalone bootstraps of coursier in particular
          case p if p.getProtocol == "file" =>
            Paths.get(p.toURI)
        }
        .filter(_.getFileName.toString.endsWith("-sources.jar"))

      sources ::: baseSourcePath
    }

    val symbolIndex = {
      val dialect =
        if (compilerManager.scalaVersion.startsWith("3."))
          dialects.Scala3
        else if (compilerManager.scalaVersion.startsWith("2.13."))
          dialects.Scala213
        else
          dialects.Scala212
      val index = OnDemandSymbolIndex.empty()
      sourcePaths.foreach(p => index.addSourceJar(AbsolutePath(p), dialect))
      index
    }

    new Docstrings(symbolIndex)
  }

  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] = {
    val pressy0 = compilerManager.pressy.compiler

    class AmmoniteSemanticDbOps(val global: pressy0.type) extends SemanticdbOps
    val semanticdbOps = new AmmoniteSemanticDbOps(pressy0)

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
    pressy0.askReload(List(currentFile), r)
    r.get.swap match {
      case Left(e) =>
        log.warn(
          s"Error loading '${code.take(pos)}|${code.drop(pos)}' into presentation compiler",
          e
        )
        None
      case Right(()) =>
        val r0 = new scala.tools.nsc.interactive.Response[pressy0.Tree]
        pressy0.askTypeAt(new scala.reflect.internal.util.OffsetPosition(currentFile, index), r0)
        r0.get.swap match {
          case Left(e) =>
            log.debug(
              s"Getting type info for '${code.take(pos)}|${code.drop(pos)}' via presentation compiler",
              e
            )
            None
          case Right(tree) =>
            val symbol = tree.symbol
            if (symbol == null)
              None
            else {
              val sym = {
                if (!symbol.isJava && symbol.isPrimaryConstructor) symbol.owner
                else symbol
              }.toSemantic
              log.debug(s"Symbol for '${code.take(pos)}|${code.drop(pos)}' is $sym")

              val typeStr = ScalaInterpreterInspections.typeOfTree(pressy0)(tree)
                .get
                .fold(
                  identity,
                  { e =>
                    log.warn("Error getting type string", e)
                    None
                  }
                )
                .getOrElse(tree.toString)

              val documentation = docs.documentation(
                sym,
                () => symbol.allOverriddenSymbols.map(_.toSemantic).toList.asJava
              )

              val text       = if (documentation.isPresent) Some(documentation.get()) else None
              val docstrings = text.fold("")(_.docstring())
              log.debug(s"Docstring for '${code.take(pos)}|${code.drop(pos)}' is $docstrings")

              import scalatags.Text.all._

              val typeHtml = div(
                pre(typeStr),
                p(docstrings)
              )

              val res = Inspection.fromDisplayData(
                DisplayData.html(typeHtml.toString)
              )

              Some(res)
            }
        }
    }
  }

  def shutdown(): Unit = ()
}

object ScalaInterpreterInspections {

  private def baseSourcePath(loader: ClassLoader, log: Logger): List[Path] = {

    lazy val javaDirs = {
      val l = Seq(sys.props("java.home")) ++
        sys.props.get("java.ext.dirs")
          .toSeq
          .flatMap(_.split(File.pathSeparator))
          .filter(_.nonEmpty) ++
        sys.props.get("java.endorsed.dirs")
          .toSeq
          .flatMap(_.split(File.pathSeparator))
          .filter(_.nonEmpty)
      l.map(_.stripSuffix("/") + "/")
    }

    def isJdkJar(uri: URI): Boolean =
      uri.getScheme == "file" && {
        val path = new File(uri).getAbsolutePath
        javaDirs.exists(path.startsWith)
      }

    def classpath(cl: ClassLoader): immutable.LazyList[java.net.URL] =
      if (cl == null)
        immutable.LazyList()
      else {
        val cp = cl match {
          case u: java.net.URLClassLoader => u.getURLs.to(immutable.LazyList)
          case _                          => immutable.LazyList()
        }

        cp #::: classpath(cl.getParent)
      }

    val baseJars = classpath(loader)
      .map(_.toURI)
      // assuming the JDK on the YARN machines already have those
      .filter(u => !isJdkJar(u))
      .map(Paths.get)
      .toList

    log.info(
      "Found base JARs:\n" +
        baseJars.sortBy(_.toString).map("  " + _).mkString("\n") +
        "\n"
    )

    baseJars.filter(_.getFileName.toString.endsWith("-sources.jar"))
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
        case Right(string)                    => Some(string)
        case Left(null)                       => None
        case Left(tree) if tree.tpe ne NoType => Some(tree.tpe.widen.toString)
        case _                                => None
      }
    }
}
