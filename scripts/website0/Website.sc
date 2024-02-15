import $ivy.`org.jsoup:jsoup:1.10.3`

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.file.Files

import scala.annotation.tailrec

object Util {
  def waitForDir(dir: File): Unit = {

    @tailrec
    def helper(): Unit = {
      val found =
        dir.exists() && {
          assert(dir.isDirectory)
          dir.listFiles().nonEmpty
        }

      if (!found) {
        Thread.sleep(200L)
        helper()
      }
    }

    helper()
  }

  def runCmd(cmd: Seq[String], dir: File = null) = {
    val b = new ProcessBuilder(cmd: _*)
    b.inheritIO()
    for (d <- Option(dir))
      b.directory(d)
    System.err.println(s"Running ${cmd.mkString(" ")}")
    val p       = b.start()
    val retCode = p.waitFor()
    if (retCode != 0)
      sys.error(s"Error running ${cmd.mkString(" ")} (return code: $retCode)")
  }

  def withBgProcess[T](
    cmd: Seq[String],
    dir: File = new File("."),
    waitFor: () => Unit = null
  )(f: => T): T = {

    val b = new ProcessBuilder(cmd: _*)
    b.inheritIO()
    b.directory(dir)
    var p: Process = null

    Option(waitFor) match {
      case Some(w) =>
        val t = new Thread("wait-for-condition") {
          setDaemon(true)
          override def run() = {
            w()
            System.err.println(s"Running ${cmd.mkString(" ")}")
            p = b.start()
          }
        }
        t.start()
      case None =>
        System.err.println(s"Running ${cmd.mkString(" ")}")
        p = b.start()
    }

    try f
    finally {
      p.destroy()
      p.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS)
      p.destroyForcibly()
    }
  }

  def outputOf(cmd: Seq[String]): String = {
    // stuff in scala.sys.process should allow to do that in a straightforward way
    // not using it here to circumvent https://github.com/scala/bug/issues/9824

    val b = new ProcessBuilder(cmd: _*)
    b.redirectOutput(ProcessBuilder.Redirect.PIPE)
    b.redirectError(ProcessBuilder.Redirect.INHERIT)
    System.err.println(s"Running ${cmd.mkString(" ")}")
    val p = b.start()

    // Closing stdin so that sbt doesn't wait indefinitely for input
    p.getOutputStream.close()

    // inspired by https://stackoverflow.com/a/16714180/3714539
    val reader       = new BufferedReader(new InputStreamReader(p.getInputStream))
    val builder      = new StringBuilder
    var line: String = null
    while ({ line = reader.readLine(); line != null }) {
      builder.append(line)
      builder.append(sys.props("line.separator"))
    }
    val result  = builder.toString
    val retCode = p.waitFor()
    if (retCode != 0)
      sys.error(s"Error running ${cmd.mkString(" ")} (return code: $retCode)")
    result
  }

  def cached(name: String)(f: => String): String = {
    val file = new File(s"target/site-cache/$name")
    // not thread/concurrency-safe…
    if (file.exists())
      new String(Files.readAllBytes(file.toPath), "UTF-8")
    else {
      val s = f
      file.getParentFile.mkdirs()
      Files.write(file.toPath, s.getBytes("UTF-8"))
      s
    }
  }
}

object Relativize {
  // from https://github.com/olafurpg/sbt-docusaurus/blob/16e548280117d3fcd8db4c244f91f089470b8ee7/plugin/src/main/scala/sbtdocusaurus/internal/Relativize.scala

  import java.net.URI
  import java.nio.charset.Charset
  import java.nio.charset.StandardCharsets
  import java.nio.file._
  import java.nio.file.attribute.BasicFileAttributes

  import org.jsoup.Jsoup
  import org.jsoup.nodes.Element

  import scala.jdk.CollectionConverters._

  def relativize(site: Path): Unit =
    Files.walkFileTree(
      site,
      new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (file.getFileName.toString.endsWith(".html"))
            processHtmlFile(site, file)
          super.visitFile(file, attrs)
        }
      }
    )

  // actual host name doesn't matter
  private val baseUri = URI.create("http://example.com/")

  def processHtmlFile(site: Path, file: Path): Unit = {
    val originRelativeUri = relativeUri(site.relativize(file))
    val originUri         = baseUri.resolve(originRelativeUri)
    val originPath        = Paths.get(originUri.getPath).getParent
    def relativizeAttribute(element: Element, attribute: String): Unit = {
      val absoluteHref = URI.create(element.attr(s"abs:$attribute"))
      if (absoluteHref.getHost == baseUri.getHost) {
        val hrefPath     = Paths.get(absoluteHref.getPath)
        val relativeHref = originPath.relativize(hrefPath)
        val fragment =
          if (absoluteHref.getFragment == null) ""
          else "#" + absoluteHref.getFragment
        val newHref = relativeUri(relativeHref).toString + fragment
        element.attr(attribute, newHref)
      }
      else if (element.attr(attribute).startsWith("//"))
        // We force "//hostname" links to become "https://hostname" in order to make
        // the site browsable without file server. If we keep "//hostname"  unchanged
        // then users will try to load "file://hostname" which results in 404.
        // We hardcode https instead of http because it's OK to load https from http
        // but not the other way around.
        element.attr(attribute, "https:" + element.attr(attribute))
    }
    val doc = Jsoup.parse(file.toFile, StandardCharsets.UTF_8.name(), originUri.toString)
    def relativizeElement(element: String, attribute: String): Unit =
      doc.select(element).forEach { element =>
        relativizeAttribute(element, attribute)
      }
    relativizeElement("a", "href")
    relativizeElement("link", "href")
    relativizeElement("img", "src")
    val renderedHtml = doc.outerHtml()
    Files.write(file, renderedHtml.getBytes(StandardCharsets.UTF_8))
  }

  private def relativeUri(relativePath: Path): URI = {
    require(!relativePath.isAbsolute, relativePath)
    val names = relativePath.iterator().asScala
    val uris = names.map { name =>
      new URI(null, null, name.toString, null)
    }
    URI.create(uris.mkString("", "/", ""))
  }
}
