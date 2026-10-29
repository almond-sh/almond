package almond.internals

import almond.logger.Logger
import ammonite.runtime.Frame

import java.io.{File, IOException}
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.zip.{ZipException, ZipFile}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** The source JARs of the class path of a session, that inspections read docs from.
  *
  * Those are the source JARs that the kernel launcher puts in its class path (alongside the JARs of
  * the kernel and of its dependencies), and those added to the session, like the ones Ammonite
  * fetches for `import $ivy`, or the ones passed via `--extra-class-path` or `interp.load.cp`.
  */
object SourcePath {

  def sourcePath(frames: List[Frame], log: Logger): Seq[Path] =
    session(frames, log) ++ base(frames, log)

  /** The source JARs added to the session */
  def session(frames: List[Frame], log: Logger): Seq[Path] = {
    val sessionJars = frames
      .flatMap(_.classpath)
      .collect {
        // FIXME We're ignoring jars-in-jars of standalone bootstraps of coursier in particular
        case p if p.getProtocol == "file" =>
          Paths.get(p.toURI)
      }
    sourcePathFromJars(sessionJars, log)
  }

  /** The source JARs of the class path the kernel was started with */
  def base(frames: List[Frame], log: Logger): Seq[Path] =
    baseSourcePath(frames.last.classloader.getParent, log)

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

    def classpath(cl: ClassLoader): List[java.net.URL] =
      if (cl == null)
        Nil
      else {
        val cp = cl match {
          case u: java.net.URLClassLoader => u.getURLs.toList
          case cl0 if cl0.toString.startsWith("jdk.internal.loader.ClassLoaders$AppClassLoader") =>
            Option(sys.props("java.class.path"))
              .map(_.split(File.pathSeparator).map(Paths.get(_).toUri.toURL).toList)
              .getOrElse(Nil)
          case _ => Nil
        }

        cp ::: classpath(cl.getParent)
      }

    val baseJars = classpath(loader)
      .map(_.toURI)
      // assuming the JDK on the YARN machines already have those
      .filter(u => !isJdkJar(u))
      .map(Paths.get)

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
    sourcePathFromJars(baseJars, log, checkForSources = checkForSources)
  }

  private def sourcePathFromJars(
    jars: Seq[Path],
    log: Logger,
    checkForSources: Boolean = false
  ): Seq[Path] = {

    val sources = new mutable.ListBuffer[Path]

    // class paths can have entries that don't exist, like resource directories of
    // build tools
    for (jar <- jars if Files.exists(jar)) {
      val name = jar.getFileName.toString
      if (name.endsWith("-sources.jar"))
        sources += jar
      else if (name.endsWith(".jar")) {
        if (checkForSources && containsSources(jar, log))
          sources += jar
      }
      // Files that aren't JARs, like the launcher in the class path when the kernel is
      // started via a coursier bootstrap, are only indexed if they contain sources. Indexing
      // anything else makes mtags print stack traces for each of its entries.
      else if (Files.isDirectory(jar) || containsSources(jar, log))
        sources += jar
    }

    sources.toList
  }

  private def containsSources(jar: Path, log: Logger): Boolean = {
    var zf: ZipFile = null
    try {
      zf = new ZipFile(jar.toFile)
      zf.entries().asScala.exists { ent =>
        val name = ent.getName
        name.endsWith(".scala") || name.endsWith(".java")
      }
    }
    catch {
      case e: ZipException =>
        log.warn(
          s"Ignoring $jar when looking for sources, as it could not be read as a ZIP file",
          e
        )
        false
      case e: IOException =>
        log.warn(s"Ignoring $jar when looking for sources, as it could not be read", e)
        false
    }
    finally
      if (zf != null)
        zf.close()
  }
}
