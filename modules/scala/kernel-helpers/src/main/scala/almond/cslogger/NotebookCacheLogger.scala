package almond.cslogger

import almond.interpreter.api.OutputHandler
import almond.logger.LoggerContext

import java.lang.{Long => JLong}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._

class NotebookCacheLogger(
  publish: OutputHandler,
  logCtx: LoggerContext
) extends coursierapi.CacheLogger {
  private val log = logCtx(getClass)
  import scalatags.Text.all._
  private val ids         = new ConcurrentHashMap[String, String]
  private val totalLength = new ConcurrentHashMap[String, JLong]
  private var threadOpt   = Option.empty[Thread]
  // display id -> latest HTML for it, not sent yet
  private val updates           = new ConcurrentHashMap[String, String]
  @volatile private var running = false
  private def newThread(): Thread =
    new Thread("cs-logger") {
      setDaemon(true)
      def proceed(): Unit =
        if (publish.canOutput()) {
          val entries = updates.entrySet().asScala.toVector.map(e => (e.getKey, e.getValue))
          for ((k, v) <- entries) {
            publish.updateHtml(v, k)
            updates.remove(k, v)
          }
        }
      override def run(): Unit = {
        try
          while (running) {
            proceed()
            Thread.sleep(25L)
          }
        catch {
          case _: InterruptedException =>
        }
        // sends the updates that came in since the last round
        proceed()
      }
    }
  override def init(sizeHint: Integer): Unit = {
    stop()
    ids.clear()
    updates.clear()
    totalLength.clear()
    running = true
    val t = newThread()
    t.start()
    threadOpt = Some(t)
  }

  /** Stops the thread sending updates, once it sent the pending ones */
  override def stop(): Unit =
    for (t <- threadOpt) {
      // not interrupting the thread, which might be sending messages at that moment
      running = false
      t.join()
      threadOpt = None
    }
  override def downloadingArtifact(url: String, artifact: coursierapi.Artifact): Unit =
    if (publish.canOutput()) {
      val newId       = UUID.randomUUID().toString
      val formerIdOpt = Option(ids.putIfAbsent(url, newId))
      val id          = formerIdOpt.getOrElse(newId)
      val html        = div("Downloading ", a(href := url, url))
      publish.html(html.render, id)
    }
  override def downloadProgress(url: String, downloaded: Long): Unit = {
    val idOpt = Option(ids.get(url))
    for (id <- idOpt; len <- Option(totalLength.get(url))) {
      val pct  = math.floor(100 * (downloaded.toDouble / len)).toInt
      val html = div("Downloading ", a(href := url, url), s" ($pct %)")
      updates.put(id, html.render)
    }
  }
  override def downloadedArtifact(url: String, success: Boolean): Unit = {
    val idOpt = Option(ids.get(url))
    idOpt match {
      case Some(id) =>
        val msg = if (success) "" else div("Failed to download ", a(href := url, url)).render
        updates.put(id, msg)
      case None =>
        if (publish.canOutput()) {
          val msg  = if (success) "Downloaded" else "Failed to download"
          val html = div(s"$msg ", a(href := url, url))
          publish.html(html.render)
        }
    }
  }
  override def downloadLength(
    url: String,
    totalLength0: Long,
    alreadyDownloaded: Long,
    watching: Boolean
  ): Unit = {
    totalLength.put(url, totalLength0: JLong)
  }
}
