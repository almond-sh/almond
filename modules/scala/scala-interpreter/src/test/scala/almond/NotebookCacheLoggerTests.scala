package almond

import almond.cslogger.NotebookCacheLogger
import almond.interpreter.api.{DisplayData, OutputHandler}
import almond.logger.LoggerContext
import utest._

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters._

object NotebookCacheLoggerTests extends TestSuite {

  /** Records what's displayed and updated */
  final class RecordingOutputHandler extends OutputHandler {
    private val events0                    = new ConcurrentLinkedQueue[(String, DisplayData)]
    def events: Seq[(String, DisplayData)] = events0.asScala.toVector

    def displayed: Seq[DisplayData] = events.collect { case ("display", d) => d }

    /** Latest content of each display, in the order they were displayed */
    def current: Seq[DisplayData] = {
      val latest = events.collect { case (_, d) => d }.foldLeft(Map.empty[String, DisplayData]) {
        (acc, d) => acc + (d.idOpt.getOrElse("") -> d)
      }
      displayed.map(d => latest(d.idOpt.getOrElse("")))
    }

    def stdout(s: String): Unit                       = ()
    def stderr(s: String): Unit                       = ()
    def display(displayData: DisplayData): Unit       = events0.add(("display", displayData))
    def updateDisplay(displayData: DisplayData): Unit = events0.add(("update", displayData))
    def clearOutput(waitForNewOutput: Boolean): Unit  = ()
    def canOutput(): Boolean                          = true
    def messageIdOpt: Option[String]                  = None
    def addPayload(payload: String): Unit             = ()
  }

  private def content(d: DisplayData, mimeType: String): Option[String] =
    d.detailedData.get(mimeType).flatMap(_.asString)
  private def html(d: DisplayData): String =
    content(d, "text/html").getOrElse("")

  private val url = "https://repo1.maven.org/maven2/org/a/b/1.0/b-1.0.jar"

  /** Runs `f` between the logger's init and stop, like coursier does */
  private def withLogger(f: NotebookCacheLogger => Unit): RecordingOutputHandler = {
    val handler = new RecordingOutputHandler
    val logger  = new NotebookCacheLogger(handler, LoggerContext.nop)
    logger.init(null)
    try f(logger)
    finally logger.stop()
    handler
  }

  val tests = Tests {

    test("downloads are shown, then cleared") {
      val handler = withLogger { logger =>
        logger.downloadingArtifact(url, coursierapi.Artifact.of(url))
        logger.downloadLength(url, 100L, 0L, false)
        logger.downloadProgress(url, 50L)
        logger.downloadedArtifact(url, true)
      }
      val displayed = handler.displayed
      assert(displayed.length == 1)
      assert(html(displayed.head).contains(s"""Downloading <a href="$url">"""))
      val current = handler.current
      assert(current.length == 1)
      assert(html(current.head).isEmpty)
    }

    test("lines have a text version") {
      val handler = withLogger { logger =>
        logger.downloadingArtifact(url, coursierapi.Artifact.of(url))
        logger.downloadLength(url, 100L, 0L, false)
        logger.downloadProgress(url, 50L)
      }
      val displayed = handler.displayed
      assert(displayed.length == 1)
      assert(content(displayed.head, "text/plain") == Some(s"Downloading $url"))
      val current = handler.current
      assert(current.length == 1)
      assert(content(current.head, "text/plain") == Some(s"Downloading $url (50 %)"))
    }

    test("lines are cleared in all versions") {
      val handler = withLogger { logger =>
        logger.downloadingArtifact(url, coursierapi.Artifact.of(url))
        logger.downloadedArtifact(url, true)
      }
      val current = handler.current
      assert(current.length == 1)
      assert(content(current.head, "text/plain") == Some(""))
      assert(content(current.head, "text/html") == Some(""))
    }

    test("failed downloads are cleared") {
      // like files missing from the first repository, found in the next one
      val handler = withLogger { logger =>
        logger.downloadingArtifact(url, coursierapi.Artifact.of(url))
        logger.downloadedArtifact(url, false)
      }
      val current = handler.current
      assert(current.length == 1)
      assert(html(current.head).isEmpty)
    }

    test("nothing shown for downloads without a line") {
      val handler = withLogger { logger =>
        logger.downloadedArtifact(url, true)
        logger.downloadedArtifact(url + ".sha1", false)
      }
      assert(handler.events.isEmpty)
    }

    test("checksum files aren't shown") {
      val handler = withLogger { logger =>
        for (ext <- Seq(".md5", ".sha1", ".sha256", ".sha512")) {
          val url0 = url + ext
          logger.downloadingArtifact(url0, coursierapi.Artifact.of(url0))
          logger.downloadedArtifact(url0, true)
        }
      }
      assert(handler.events.isEmpty)
    }

    test("progress is shown") {
      val handler = withLogger { logger =>
        logger.downloadingArtifact(url, coursierapi.Artifact.of(url))
        logger.downloadLength(url, 100L, 0L, false)
        logger.downloadProgress(url, 50L)
      }
      val current = handler.current
      assert(current.length == 1)
      assert(html(current.head).contains("(50 %)"))
    }
  }
}
