package almond.cslogger

import almond.interpreter.api.OutputHandler
import almond.logger.LoggerContext

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NotebookCacheLogger(
  publish: OutputHandler,
  logCtx: LoggerContext
) extends coursierapi.CacheLogger {
  private val log = logCtx(getClass)
  import scalatags.Text.all._
  var ids = new ConcurrentHashMap[String, String]
  override def init(sizeHint: Integer): Unit = {
    ids.clear()
  }
  override def stop(): Unit = {}
  override def downloadingArtifact(url: String, artifact: coursierapi.Artifact): Unit = {
    if (publish.canOutput()) {
      val newId       = UUID.randomUUID().toString
      val formerIdOpt = Option(ids.putIfAbsent(url, newId))
      val id          = formerIdOpt.getOrElse(newId)
      val html        = div("Downloading ", a(href := url, url))
      publish.html(html.render, id)
    }
  }
  override def downloadProgress(url: String, downloaded: Long): Unit = {}
  override def downloadedArtifact(url: String, success: Boolean): Unit = {
    if (publish.canOutput()) {
      val idOpt = Option(ids.get(url))
      val msg   = if (success) "Downloaded" else "Failed to download"
      val html  = div(s"$msg ", a(href := url, url))
      idOpt match {
        case Some(id) =>
          publish.updateHtml(html.render, id)
        case None =>
          publish.html(html.render)
      }
    }
  }
  override def downloadLength(
    url: String,
    totalLength: Long,
    alreadyDownloaded: Long,
    watching: Boolean
  ): Unit = {}
}
