package almond.internals

import java.nio.file.{Path, Paths}

import almond.interpreter._
import almond.interpreter.api.DisplayData
import almond.logger.{Logger, LoggerContext}
import ammonite.runtime.Frame
import ammonite.util.Util.newLine
import scala.meta.dialects
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.{IndexingExceptions, OnDemandSymbolIndex}
import scala.meta.internal.semanticdb.scalac.SemanticdbOps
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.reports.EmptyReportContext

import java.io.File
import java.net.URI
import java.util.Optional
import java.util.zip.ZipFile

import scala.collection.compat._
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.nsc.interactive.{Global => Interactive}
import scala.util.Properties

final class ScalaInterpreterInspections(
  logCtx: LoggerContext,
  metabrowse: Boolean,
  metabrowseHost: String,
  metabrowsePort: Int,
  scalaVersion: String,
  compilerManager: => ammonite.compiler.CompilerLifecycleManager,
  frames: => List[Frame]
) {

  private val log = logCtx(getClass)

  private lazy val docs: Docstrings = {

    val sourcePaths = ScalaInterpreterInspections.sourcePath(frames, log)

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
      val index = OnDemandSymbolIndex.empty()(new EmptyReportContext)
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

    new Docstrings(symbolIndex)(new EmptyReportContext)
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

                val documentation =
                  if (sym.isEmpty)
                    Optional.empty[SymbolDocumentation]()
                  else
                    try
                      docs.documentation(
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

  def sourcePath(frames: List[Frame], log: Logger): Seq[Path] = {
    val baseSourcepath = baseSourcePath(
      frames
        .last
        .classloader
        .getParent,
      log
    )

    val sessionJars = frames
      .flatMap(_.classpath)
      .collect {
        // FIXME We're ignoring jars-in-jars of standalone bootstraps of coursier in particular
        case p if p.getProtocol == "file" =>
          Paths.get(p.toURI)
      }

    sourcePathFromJars(sessionJars) ++ baseSourcepath
  }

  private def baseSourcePath(loader: ClassLoader, log: Logger): Seq[Path] = {

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
          case cl0 if cl0.toString.startsWith("jdk.internal.loader.ClassLoaders$AppClassLoader") =>
            Option(sys.props("java.class.path"))
              .map(_.split(File.pathSeparator).map(Paths.get(_).toUri.toURL).to(immutable.LazyList))
              .getOrElse(immutable.LazyList())
          case _ => immutable.LazyList()
        }

        cp #::: classpath(cl.getParent)
      }

    val baseJars = classpath(loader)
      .map(_.toURI)
      // assuming the JDK on the YARN machines already have those
      .filter(u => !isJdkJar(u))
      .map(Paths.get)
      .toList

    log.info {
      val nl = System.lineSeparator()
      "Found base JARs:" + nl +
        baseJars.sortBy(_.toString).map("  " + _).mkString(nl) + nl
    }

    // When using a "hybrid" launcher, and users decided to end its name with ".jar",
    // we still want to use it as a source JAR too. So we check if it contains sources here.
    val checkForSources =
      baseJars.exists(_.getFileName.toString.endsWith(".jar")) &&
      !baseJars.exists(_.getFileName.toString.endsWith("-sources.jar"))
    sourcePathFromJars(baseJars, checkForSources = checkForSources)
  }

  private def sourcePathFromJars(jars: Seq[Path], checkForSources: Boolean = false): Seq[Path] = {

    val sources = new mutable.ListBuffer[Path]

    for (jar <- jars) {
      val name = jar.getFileName.toString
      if (name.endsWith("-sources.jar"))
        sources += jar
      else if (name.endsWith(".jar")) {
        if (checkForSources) {
          val foundSources = {
            var zf: ZipFile = null
            try {
              zf = new ZipFile(jar.toFile)
              zf.entries().asScala.exists { ent =>
                val name = ent.getName
                name.endsWith(".scala") || name.endsWith(".java")
              }
            }
            finally
              if (zf != null)
                zf.close()
          }
          if (foundSources)
            sources += jar
        }
      }
      else
        sources += jar
    }

    sources.toList
  }
}
