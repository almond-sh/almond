package jupyter.scala

import java.io.{IOException, File}
import java.net.{URISyntaxException, URL}
import java.util.Collections

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.apache.ivy.Ivy
import org.apache.ivy.core.module.descriptor.{Artifact => IArtifact, DependencyDescriptor, DefaultDependencyDescriptor, DefaultModuleDescriptor}
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.resolve.{ResolveData, ResolveOptions}
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter
import org.apache.ivy.plugins.repository.{TransferEvent, RepositoryCopyProgressListener}
import org.apache.ivy.plugins.repository.file.{FileRepository, FileResource}
import org.apache.ivy.plugins.repository.url.URLRepository
import org.apache.ivy.plugins.resolver._
import org.apache.ivy.util._
import org.xml.sax.SAXParseException

import scala.xml.XML

// Snippets from Ammonite's IvyThing and sbt-ivy

object IvyHelper extends LazyLogging {

  Message setDefaultLogger new AbstractMessageLogger {
    val maxLevel = 2

    def doEndProgress(msg: String) =
      Console.err println "Done"
    def doProgress() =
      Console.err print "."
    def log(msg: String, level: Int): Unit =
      if (level <= maxLevel)
        Console.err println s"($level) $msg"
    def rawlog(msg: String, level: Int): Unit =
      if (level <= maxLevel)
        Console.err println s"($level, raw) $msg"
  }

  private def setResolvers(settings: IvySettings, resolvers: Seq[DependencyResolver]) {
    val chain = new SbtChainResolver("sbt-chain", resolvers, settings)
    settings.addResolver(chain)
    settings.setDefaultResolver(chain.getName)
  }

  def resolveArtifact(groupId: String, artifactId: String, version: String, resolvers: Seq[DependencyResolver]) = {
    val ivy = Ivy.newInstance {
      val ivySettings = new IvySettings()
      ivySettings.setBaseDir(ResolverHelpers.ivyHome)
      setResolvers(ivySettings, resolvers)
      ivySettings
    }

    val ivyfile = File.createTempFile("ivy", ".xml")
    ivyfile.deleteOnExit()

    val md = DefaultModuleDescriptor.newDefaultInstance(
      ModuleRevisionId.newInstance(
        groupId,
        artifactId + "-caller",
        "working"
      )
    )

    md.addDependency(
      new DefaultDependencyDescriptor(
        md,
        ModuleRevisionId.newInstance(groupId, artifactId, version),
        false,
        false,
        true
      )
    )

    //creates an ivy configuration file
    XmlModuleDescriptorWriter.write(md, ivyfile)

    logger debug s"Ivy file: ${io.Source.fromFile(ivyfile).mkString}"

    //init resolve report
    val report = ivy.resolve(
      ivyfile.toURI.toURL,
      new ResolveOptions().setConfs(Array("default")).setRefresh(true).setOutputReport(false)
    )
    //so you can get the jar libraries
    report.getAllArtifactsReports.map(_.getLocalFile)
  }
}

object ResolverHelpers {

  private object ChecksumFriendlyURLResolver {
    // TODO - When we dump JDK6 support we can remove this hackery
    // import java.lang.reflect.AccessibleObject
    type AccessibleObject = {
      def setAccessible(value: Boolean): Unit
    }
    private def reflectiveLookup[A <: AccessibleObject](f: Class[_] => A): Option[A] =
      try {
        val cls = classOf[RepositoryResolver]
        val thing = f(cls)
        import scala.language.reflectiveCalls
        thing.setAccessible(true)
        Some(thing)
      } catch {
        case (_: java.lang.NoSuchFieldException) |
             (_: java.lang.SecurityException) |
             (_: java.lang.NoSuchMethodException) => None
      }
    private val signerNameField: Option[java.lang.reflect.Field] =
      reflectiveLookup(_.getDeclaredField("signerName"))
    private val putChecksumMethod: Option[java.lang.reflect.Method] =
      reflectiveLookup(_.getDeclaredMethod("putChecksum",
        classOf[IArtifact], classOf[File], classOf[String],
        classOf[Boolean], classOf[String]))
    private val putSignatureMethod: Option[java.lang.reflect.Method] =
      reflectiveLookup(_.getDeclaredMethod("putSignature",
        classOf[IArtifact], classOf[File], classOf[String],
        classOf[Boolean]))
  }

  private trait ChecksumFriendlyURLResolver extends RepositoryResolver {
    import ChecksumFriendlyURLResolver._
    private def signerName: String = signerNameField match {
      case Some(field) => field.get(this).asInstanceOf[String]
      case None        => null
    }
    override protected def put(artifact: IArtifact, src: File, dest: String, overwrite: Boolean): Unit = {
      // verify the checksum algorithms before uploading artifacts!
      val checksums = getChecksumAlgorithms()
      val repository = getRepository()
      for {
        checksum <- checksums
        if !ChecksumHelper.isKnownAlgorithm(checksum)
      } throw new IllegalArgumentException("Unknown checksum algorithm: " + checksum)
      repository.put(artifact, src, dest, overwrite);
      // Fix for sbt#1156 - Artifactory will auto-generate MD5/sha1 files, so
      // we need to overwrite what it has.
      for (checksum <- checksums) {
        putChecksumMethod match {
          case Some(method) => method.invoke(this, artifact, src, dest, true: java.lang.Boolean, checksum)
          case None         => // TODO - issue warning?
        }
      }
      if (signerName != null) {
        putSignatureMethod match {
          case None         => ()
          case Some(method) => method.invoke(artifact, src, dest, true: java.lang.Boolean)
        }
      }
    }
  }

  private trait DescriptorRequired extends BasicResolver {
    override def getDependency(dd: DependencyDescriptor, data: ResolveData) =
    {
      val prev = descriptorString(isAllownomd)
      setDescriptor(descriptorString(hasExplicitURL(dd)))
      try super.getDependency(dd, data) finally setDescriptor(prev)
    }
    def descriptorString(optional: Boolean) =
      if (optional) BasicResolver.DESCRIPTOR_OPTIONAL else BasicResolver.DESCRIPTOR_REQUIRED
    def hasExplicitURL(dd: DependencyDescriptor): Boolean =
      dd.getAllDependencyArtifacts.exists(_.getUrl != null)
  }

  private final class WarnOnOverwriteFileRepo extends FileRepository() {
    override def put(source: java.io.File, destination: String, overwrite: Boolean): Unit = {
      try super.put(source, destination, overwrite)
      catch {
        case e: java.io.IOException if e.getMessage.contains("destination already exists") =>
          import org.apache.ivy.util.Message
          Message.warn(s"Attempting to overwrite $destination\n\tThis usage is deprecated and will be removed in sbt 1.0.")
          super.put(source, destination, true)
      }
    }
  }

  def toFile(url: URL): File =
    try { new File(url.toURI) }
    catch { case _: URISyntaxException => new File(url.getPath) }

  private final class LocalIfFileRepo extends URLRepository {
    private[this] val repo = new WarnOnOverwriteFileRepo()
    private[this] val progress = new RepositoryCopyProgressListener(this);
    override def getResource(source: String) = {
      val url = new URL(source)
      if (url.getProtocol == "file")
        new FileResource(repo, toFile(url))
      else
        super.getResource(source)
    }

    override def put(source: File, destination: String, overwrite: Boolean): Unit = {
      val url = new URL(destination)
      if (url.getProtocol != "file") super.put(source, destination, overwrite)
      else {
        // Here we duplicate the put method for files so we don't just bail on trying ot use Http handler
        val resource = getResource(destination)
        if (!overwrite && resource.exists()) {
          throw new IOException("destination file exists and overwrite == false");
        }
        fireTransferInitiated(resource, TransferEvent.REQUEST_PUT);
        try {
          var totalLength = source.length
          if (totalLength > 0) {
            progress.setTotalLength(totalLength);
          }
          FileUtil.copy(source, new java.io.File(url.toURI), progress)
        } catch {
          case ex: IOException =>
            fireTransferError(ex)
            throw ex
          case ex: RuntimeException =>
            fireTransferError(ex)
            throw ex
        } finally {
          progress.setTotalLength(null);
        }
      }
    }
  }

  private def resolvePattern(base: String, pattern: String): String =
  {
    val normBase = base.replace('\\', '/')
    if (normBase.endsWith("/") || pattern.startsWith("/")) normBase + pattern else normBase + "/" + pattern
  }

  private val mavenStyleBasePattern = "[organisation]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[artifact]-[revision](-[classifier]).[ext]"

  private def initializeMavenStyle(resolver: IBiblioResolver, name: String, root: String) {
    resolver.setName(name)
    resolver.setM2compatible(true)
    resolver.setRoot(root)
  }

  def mavenResolver(name: String, root: String): DependencyResolver = {
    val pattern = Collections.singletonList(resolvePattern(root, mavenStyleBasePattern))
    final class PluginCapableResolver extends IBiblioResolver with ChecksumFriendlyURLResolver with DescriptorRequired {
      def setPatterns() {
        // done this way for access to protected methods.
        setArtifactPatterns(pattern)
        setIvyPatterns(pattern)
      }
    }
    val resolver = new PluginCapableResolver
    resolver.setRepository(new LocalIfFileRepo)
    initializeMavenStyle(resolver, name, root)
    resolver.setPatterns() // has to be done after initializeMavenStyle, which calls methods that overwrite the patterns
    resolver
  }

  case class Patterns(ivyPatterns: Seq[String], artifactPatterns: Seq[String], isMavenCompatible: Boolean, descriptorOptional: Boolean = false, skipConsistencyCheck: Boolean = false)

  private def initializePatterns(resolver: AbstractPatternsBasedResolver, patterns: Patterns, settings: IvySettings) {
    resolver.setM2compatible(patterns.isMavenCompatible)
    resolver.setDescriptor(if (patterns.descriptorOptional) BasicResolver.DESCRIPTOR_OPTIONAL else BasicResolver.DESCRIPTOR_REQUIRED)
    resolver.setCheckconsistency(!patterns.skipConsistencyCheck)
    patterns.ivyPatterns.foreach(p => resolver.addIvyPattern(settings substitute p))
    patterns.artifactPatterns.foreach(p => resolver.addArtifactPattern(settings substitute p))
  }

  def urlRepository(name: String, patterns: Patterns, settings: IvySettings = new IvySettings()): DependencyResolver = {
    val resolver = new URLResolver with ChecksumFriendlyURLResolver with DescriptorRequired
    resolver.setName(name)
    initializePatterns(resolver, patterns, settings)
    resolver
  }

  val userHome = new File(System.getProperty("user.home"))

  private def mavenLocalDir: File = {
    def loadHomeFromSettings(f: () => File): Option[File] =
      try {
        val file = f()
        if (!file.exists) None
        else (XML.loadFile(file) \ "localRepository").text match {
          case ""    => None
          case e @ _ => Some(new File(e))
        }
      } catch {
        // Occurs inside File constructor when property or environment variable does not exist
        case _: NullPointerException => None
        // Occurs when File does not exist
        case _: IOException          => None
        case e: SAXParseException    => System.err.println(s"WARNING: Problem parsing ${f().getAbsolutePath}, ${e.getMessage}"); None
      }
    loadHomeFromSettings(() => new File(userHome, ".m2/settings.xml")) orElse
      loadHomeFromSettings(() => new File(new File(System.getenv("M2_HOME")), "conf/settings.xml")) getOrElse
      new File(userHome, ".m2/repository")
  }

  val mavenLocal = mavenResolver("Maven2 Local", mavenLocalDir.toURI.toURL.toString)

  val SonatypeRepositoryRoot = "https://oss.sonatype.org/content/repositories"

  def sonatypeRepo(status: String) = mavenResolver("sonatype-" + status, SonatypeRepositoryRoot + "/" + status)

  def centralRepositoryRoot(secure: Boolean) = (if (secure) "https" else "http") + "://repo1.maven.org/maven2/"

  val defaultMaven = mavenResolver("public", centralRepositoryRoot(secure = true))

  def fileRepository(name: String, patterns: Patterns, isLocal: Boolean, isTransactional: Option[Boolean], settings: IvySettings = new IvySettings()): DependencyResolver = {
    val resolver = new FileSystemResolver with DescriptorRequired {
      // Workaround for #1156
      // Temporarily in sbt 0.13.x we deprecate overwriting
      // in local files for non-changing revisions.
      // This will be fully enforced in sbt 1.0.
      setRepository(new WarnOnOverwriteFileRepo())
    }
    resolver.setName(name)
    initializePatterns(resolver, patterns, settings)
    resolver.setLocal(isLocal)
    isTransactional.foreach(value => resolver.setTransactional(value.toString))
    resolver
  }

  val PluginPattern = "(scala_[scalaVersion]/)(sbt_[sbtVersion]/)"
  val localBasePattern = "[organisation]/[module]/" + PluginPattern + "[revision]/[type]s/[artifact](-[classifier]).[ext]"

  val ivyHome = new File(userHome, ".ivy2")

  lazy val localRepo = {
    val id = "local"
    val pList = List(s"${ivyHome.getAbsolutePath}/$id/$localBasePattern")
    fileRepository(id, Patterns(pList, pList, isMavenCompatible = false), isLocal = true, isTransactional = None)
  }
}
