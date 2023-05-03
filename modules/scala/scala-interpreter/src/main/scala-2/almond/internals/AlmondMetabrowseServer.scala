package almond.internals

import java.io.File
import java.net.URI
import java.nio.file.Paths

import almond.channels.ConnectionParameters
import almond.interpreter._
import almond.logger.{Logger, LoggerContext}
import ammonite.runtime.Frame
import ammonite.util.Util.newLine
import metabrowse.server.{MetabrowseServer, Sourcepath}
import scala.meta.dialects

import scala.collection.compat._
import scala.tools.nsc.interactive.{Global => Interactive}
import scala.util.Random

final class AlmondMetabrowseServer(
  logCtx: LoggerContext,
  metabrowse: Boolean,
  metabrowseHost: String,
  metabrowsePort: Int,
  scalaVersion: String,
  frames: => List[Frame]
) {

  private val log = logCtx(getClass)

  @volatile private var metabrowseServerOpt0 = Option.empty[(MetabrowseServer, Int, String)]
  private val metabrowseServerCreateLock     = new Object

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

  private def randomPort(): Int = {
    val s    = new java.net.ServerSocket(0)
    val port = s.getLocalPort
    s.close()
    port
  }

  private def createMetabrowseServer() = {

    if (
      metabrowse &&
      !sys.props.contains("org.jboss.logging.provider") &&
      !sys.props.get("almond.adjust.jboss.logging.provider").contains("0")
    ) {
      log.info("Setting Java property org.jboss.logging.provider to slf4j")
      sys.props("org.jboss.logging.provider") = "slf4j"
    }

    val port =
      if (metabrowsePort > 0)
        metabrowsePort
      else
        randomPort()

    val dialect =
      if (scalaVersion.startsWith("3."))
        dialects.Scala3
      else if (scalaVersion.startsWith("2.13."))
        dialects.Scala213
      else
        dialects.Scala212

    val server = new MetabrowseServer(
      dialect,
      host = metabrowseHost,
      port = port
      // FIXME Pass custom logger?
    )

    val windowName = {
      val id = math.abs(Random.nextInt().toLong)
      s"almond-metabrowse-$id"
    }

    val sourcePath = AlmondMetabrowseServer.sourcePath(frames, log)

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

  def urlFor(pressy: Interactive)(
    code: String,
    pos: Int,
    detailLevel: Int,
    tree: pressy.Tree
  ): Option[(String, String)] =
    metabrowseServerOpt().flatMap {
      case (metabrowseServer, metabrowsePort0, metabrowseWindowId) =>
        val r0 = pressy.askForResponse(() => metabrowseServer.urlForSymbol(pressy)(tree.symbol))
        r0.get.swap match {
          case Left(e) =>
            log.warn(
              s"Error loading '${code.take(pos)}|${code.drop(pos)}' into presentation compiler",
              e
            )
            None
          case Right(relUrlOpt) =>
            relUrlOpt.map(relUrl =>
              (s"http://$metabrowseHost:$metabrowsePort0/$relUrl", metabrowseWindowId)
            )
        }
    }

  def shutdown(): Unit =
    for ((metabrowseServer, _, _) <- metabrowseServerOpt0) {
      log.info("Stopping metabrowse server")
      metabrowseServer.stop()
    }

}

object AlmondMetabrowseServer {

  private def baseSourcePath(loader: ClassLoader, log: Logger): Sourcepath = {

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

    val (baseSources, baseOther) = baseJars
      .partition(_.getFileName.toString.endsWith("-sources.jar"))

    Sourcepath(baseOther, baseSources)
  }

  private implicit class SourcepathOps(private val p: Sourcepath) extends AnyVal {
    def ::(other: Sourcepath): Sourcepath =
      Sourcepath(other.classpath ::: p.classpath, other.sources ::: p.sources)
  }

  def sourcePath(frames: List[Frame], log: Logger) = {
    val baseSourcepath = AlmondMetabrowseServer.baseSourcePath(
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

    val (sources, other) = sessionJars
      .partition(_.getFileName.toString.endsWith("-sources.jar"))

    Sourcepath(other, sources) :: baseSourcepath
  }

}
