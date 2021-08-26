package almond.internals

import java.io.File
import java.net.URI
import java.nio.file.Paths

import almond.channels.ConnectionParameters
import almond.interpreter._
import almond.interpreter.api.DisplayData
import almond.logger.{Logger, LoggerContext}
import ammonite.runtime.Frame
import ammonite.util.Ref
import ammonite.util.Util.newLine
import metabrowse.server.{MetabrowseServer, Sourcepath}

import scala.tools.nsc.Global
import scala.tools.nsc.interactive.{Global => Interactive}
import scala.util.Random

final class ScalaInterpreterInspections(
  logCtx: LoggerContext,
  metabrowse: Boolean,
  metabrowseHost: String,
  metabrowsePort: Int,
  compilerManager: => ammonite.compiler.CompilerLifecycleManager,
  frames: => List[Frame]
) {

  private val log = logCtx(getClass)


  @volatile private var metabrowseServerOpt0 = Option.empty[(MetabrowseServer, Int, String)]
  private val metabrowseServerCreateLock = new Object

  private def metabrowseServerOpt() =
    if (metabrowse)
      metabrowseServerOpt0.orElse {
        metabrowseServerCreateLock.synchronized {
          metabrowseServerOpt0.orElse {
            metabrowseServerOpt0 = Some(createMetabrowseServer())
            metabrowseServerOpt0
          }
        }
      }
    else
      None

  private def createMetabrowseServer() = {

    if (metabrowse && !sys.props.contains("org.jboss.logging.provider") && !sys.props.get("almond.adjust.jboss.logging.provider").contains("0")) {
      log.info("Setting Java property org.jboss.logging.provider to slf4j")
      sys.props("org.jboss.logging.provider") = "slf4j"
    }

    val port =
      if (metabrowsePort > 0)
        metabrowsePort
      else
        ConnectionParameters.randomPort()

    val server = new MetabrowseServer(
      host = metabrowseHost,
      port = port
      // FIXME Pass custom logger?
    )

    val windowName = {
      val id = math.abs(Random.nextInt().toLong)
      s"almond-metabrowse-$id"
    }

    val baseSourcepath = ScalaInterpreterInspections.baseSourcePath(
      frames
        .last
        .classloader
        .getParent,
      log
    )

    val sourcePath = {

      import ScalaInterpreterInspections.SourcepathOps

      val sessionJars = frames
        .flatMap(_.classpath)
        .collect {
          // FIXME We're ignoring jars-in-jars of standalone bootstraps of coursier in particular
          case p if p.getProtocol == "file" =>
            Paths.get(p.toURI)
        }

      val (sources, other) = sessionJars
        .partition(_.getFileName.toString.endsWith("-sources.jar"))

      Sourcepath(other, sources) :: baseSourcepath
    }

    log.info(s"Starting metabrowse server at http://$metabrowseHost:$port")
    log.info(
      "Initial source path\n  Classpath\n" +
        sourcePath.classpath.map("    " + _).mkString("\n") +
        "\n  Sources\n" +
        sourcePath.sources.map("    " + _).mkString("\n")
    )
    server.start(sourcePath)

    (server, port, windowName)
  }

  def inspect(code: String, pos: Int, detailLevel: Int): Option[Inspection] =
    metabrowseServerOpt().flatMap {
      case (metabrowseServer, metabrowsePort0, metabrowseWindowId) =>
        val pressy0 = compilerManager.pressy.compiler

        val prefix = frames.head.imports.toString() + newLine + "object InspectWrapper{" + newLine
        val suffix = newLine + "}"
        val allCode = prefix + code + suffix
        val index = prefix.length + pos

        val currentFile = new scala.reflect.internal.util.BatchSourceFile(
          ammonite.compiler.Compiler.makeFile(allCode.getBytes, name = "Current.sc"),
          allCode
        )

        val r = new scala.tools.nsc.interactive.Response[Unit]
        pressy0.askReload(List(currentFile), r)
        r.get.swap match {
          case Left(e) =>
            log.warn(s"Error loading '${code.take(pos)}|${code.drop(pos)}' into presentation compiler", e)
            None
          case Right(()) =>
            val r0 = new scala.tools.nsc.interactive.Response[pressy0.Tree]
            pressy0.askTypeAt(new scala.reflect.internal.util.OffsetPosition(currentFile, index), r0)
            r0.get.swap match {
              case Left(e) =>
                log.debug(s"Getting type info for '${code.take(pos)}|${code.drop(pos)}' via presentation compiler", e)
                None
              case Right(tree) =>

                val r0 = pressy0.askForResponse(() => metabrowseServer.urlForSymbol(pressy0)(tree.symbol))
                r0.get.swap match {
                  case Left(e) =>
                    log.warn(s"Error loading '${code.take(pos)}|${code.drop(pos)}' into presentation compiler", e)
                    None
                  case Right(relUrlOpt) =>
                    log.debug(s"url of $tree: $relUrlOpt")
                    val urlOpt = relUrlOpt.map(relUrl => s"http://$metabrowseHost:$metabrowsePort0/$relUrl")

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

                    import scalatags.Text.all._

                    val typeHtml0 = pre(typeStr)
                    val typeHtml: Frag = urlOpt.fold(typeHtml0) { url =>
                      a(href := url, target := metabrowseWindowId, typeHtml0)
                    }

                    val res = Inspection.fromDisplayData(
                      DisplayData.html(typeHtml.toString)
                    )

                    Some(res)
                }
            }
        }
      }

  def shutdown(): Unit =
    for ((metabrowseServer, _, _) <- metabrowseServerOpt0) {
      log.info("Stopping metabrowse server")
      metabrowseServer.stop()
    }

}

object ScalaInterpreterInspections {

  private def baseSourcePath(loader: ClassLoader, log: Logger): Sourcepath = {

    lazy val javaDirs = {
      val l = Seq(sys.props("java.home")) ++
        sys.props.get("java.ext.dirs").toSeq.flatMap(_.split(File.pathSeparator)).filter(_.nonEmpty) ++
        sys.props.get("java.endorsed.dirs").toSeq.flatMap(_.split(File.pathSeparator)).filter(_.nonEmpty)
      l.map(_.stripSuffix("/") + "/")
    }

    def isJdkJar(uri: URI): Boolean =
      uri.getScheme == "file" && {
        val path = new File(uri).getAbsolutePath
        javaDirs.exists(path.startsWith)
      }

    def classpath(cl: ClassLoader): Stream[java.net.URL] = {
      if (cl == null)
        Stream()
      else {
        val cp = cl match {
          case u: java.net.URLClassLoader => u.getURLs.toStream
          case _ => Stream()
        }

        cp #::: classpath(cl.getParent)
      }
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

    val (baseSources, baseOther) = baseJars
      .partition(_.getFileName.toString.endsWith("-sources.jar"))

    Sourcepath(baseOther, baseSources)
  }

  private implicit class SourcepathOps(private val p: Sourcepath) extends AnyVal {
    def ::(other: Sourcepath): Sourcepath =
      Sourcepath(other.classpath ::: p.classpath, other.sources ::: p.sources)
  }

  // from https://github.com/scalameta/metals/blob/cec8b98cba23110d5b2919d9879c78d3b0146ab2/metaserver/src/main/scala/scala/meta/languageserver/providers/HoverProvider.scala#L34-L51
  // (via https://github.com/almond-sh/almond/pull/235#discussion_r222696661)
  private def typeOfTree(c: Interactive)(t: c.Tree): c.Response[Option[String]] =
    c.askForResponse { () =>
      import c._

      val stringOrTree = t match {
        case t: DefDef => Right(t.symbol.asMethod.info.toLongString)
        case t: ValDef if t.tpt != null => Left(t.tpt)
        case t: ValDef if t.rhs != null => Left(t.rhs)
        case x => Left(x)
      }

      stringOrTree match {
        case Right(string) => Some(string)
        case Left(null) => None
        case Left(tree) if tree.tpe ne NoType => Some(tree.tpe.widen.toString)
        case _ => None
      }
    }

}
