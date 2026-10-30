package almond.internals

import almond.logger.Logger
import ammonite.runtime.Frame

import java.io.{File, IOException}
import java.net.{URI, URL}
import java.nio.file.{FileSystem, FileSystems, Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.{ZipException, ZipFile}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** The source JARs of the class path of a session, that inspections read docs from.
  *
  * Those are the source JARs that the kernel launcher puts in its class path (alongside the JARs of
  * the kernel and of its dependencies), and those added to the session, like the ones Ammonite
  * fetches for `import $ivy`, or the ones passed via `--extra-class-path` or `interp.load.cp`.
  *
  * The JARs embedded in standalone launchers (see `cs bootstrap --standalone`), that are in the
  * class path via URLs like
  * `jar:file:/path/to/launcher!/coursier/bootstrap/launcher/jars/lib.jar!/`, are paths in a ZIP
  * file system of their launcher here. Use [[localFile]] to read them as files.
  */
object SourcePath {

  def sourcePath(frames: List[Frame], log: Logger): Seq[Path] =
    session(frames, log) ++ base(frames, log)

  /** The source JARs added to the session */
  def session(frames: List[Frame], log: Logger): Seq[Path] = {
    val sessionJars = frames
      .flatMap(_.classpath)
      .flatMap(classPathEntry(_, log))
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

    def isJdkJar(url: URL): Boolean =
      url.getProtocol == "file" && {
        val path = new File(url.toURI).getAbsolutePath
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
      // assuming the JDK on the YARN machines already have those
      .filter(u => !isJdkJar(u))
      .flatMap(classPathEntry(_, log))

    log.info {
      val nl = System.lineSeparator()
      "Found base JARs:" + nl +
        baseJars.map(_.toUri.toString).sorted.map("  " + _).mkString(nl) + nl
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
      zf = new ZipFile(localFile(jar).toFile)
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

  /** The path of a class path entry
    *
    * @param url
    *   either a `file:` URL, or a `jar:` URL of a JAR or of a JAR it embeds, like
    *   `jar:file:/path/to/launcher!/coursier/bootstrap/launcher/jars/lib.jar!/`
    */
  def classPathEntry(url: URL, log: Logger): Option[Path] =
    url.getProtocol match {
      case "file" => Some(Paths.get(url.toURI))
      case "jar"  => jarPath(url.toString.stripPrefix("jar:").stripSuffix("!/"), log)
      case _      => None
    }

  /** The path of the JAR a resource is read from
    *
    * @param url
    *   the URL of a resource read from a JAR, like `jar:file:/path/to/lib.jar!/foo/Bar.class` or,
    *   for JARs embedded in standalone launchers,
    *   `jar:file:/path/to/launcher!/coursier/bootstrap/launcher/jars/lib.jar!/foo/Bar.class`
    */
  def resourceJar(url: URL, log: Logger): Option[Path] =
    if (url.getProtocol == "jar") {
      val str = url.toString.stripPrefix("jar:")
      val sep = str.lastIndexOf("!/")
      if (sep < 0) None
      else jarPath(str.take(sep), log)
    }
    else
      None

  // Like `file:/path/to/lib.jar`, or `file:/path/to/launcher!/path/to/lib.jar`
  private def jarPath(spec: String, log: Logger): Option[Path] = {
    val sep      = spec.indexOf("!/")
    val outerUri =
      try Some(new URI(if (sep < 0) spec else spec.take(sep)))
      catch {
        case e: java.net.URISyntaxException =>
          log.debug(s"Ignoring malformed class path entry $spec", e)
          None
      }
    outerUri.filter(_.getScheme == "file").map(Paths.get).flatMap { outer =>
      if (sep < 0) Some(outer)
      else {
        val entry = spec.drop(sep + 2)
        // JARs nested more than once aren't supported
        if (entry.isEmpty || entry.contains("!/")) None
        else zipFileSystem(outer, log).map(_.getPath(entry))
      }
    }
  }

  // ZIP file systems of the standalone launchers, opened once for the whole JVM life, like the
  // class loaders reading their JARs keep them open
  private val zipFileSystems = new ConcurrentHashMap[Path, Option[FileSystem]]

  private def zipFileSystem(path: Path, log: Logger): Option[FileSystem] =
    zipFileSystems.computeIfAbsent(
      path,
      _ =>
        try Some(FileSystems.newFileSystem(path, null: ClassLoader))
        catch {
          case e: IOException =>
            log.warn(s"Could not open $path as a ZIP file", e)
            None
        }
    )

  // the files of localFile, of JARs that aren't in the default file system
  private val localFiles         = new ConcurrentHashMap[Path, Path]
  private lazy val localFilesDir = {
    val dir = Files.createTempDirectory("almond-sources")
    dir.toFile.deleteOnExit()
    dir
  }

  /** A file of the default file system with the content of `path`
    *
    * `path` itself if it's in the default file system already, or a copy of it in a temporary
    * directory otherwise, like for JARs embedded in standalone launchers (see [[classPathEntry]]).
    * These copies are made once, and deleted when the JVM exits.
    */
  def localFile(path: Path): Path =
    if (path.getFileSystem == FileSystems.getDefault) path
    else
      localFiles.computeIfAbsent(
        path,
        { _ =>
          // in a directory of its own, so that the copy keeps the name of the original
          val dir = Files.createTempDirectory(localFilesDir, "jar")
          dir.toFile.deleteOnExit()
          val dest = dir.resolve(path.getFileName.toString)
          dest.toFile.deleteOnExit()
          Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
          dest
        }
      )
}
