package almond.internals

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

import almond.interpreter.api.DisplayData
import almond.logger.LoggerContext
import ammonite.util.Ref

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

final class UpdatableResults(
  ec: ExecutionContext,
  logCtx: LoggerContext,
  updateData: DisplayData => Unit
) {

  private val log = logCtx(getClass)

  val refs = new ConcurrentHashMap[String, (DisplayData, Ref[Map[String, String]])]

  val addRefsLock = new Object

  val earlyUpdates = new mutable.HashMap[String, (String, Boolean)]

  def add(data: DisplayData, variables: Map[String, String]): DisplayData = {
    val ref = (data, Ref(variables))
    addRefsLock.synchronized {
      val variables0 = variables.map {
        case (k, v) =>
          val vOpt = earlyUpdates.remove(k)
          if (!vOpt.exists(_._2))
            refs.put(k, ref)
          k -> vOpt.fold(v)(_._1)
      }
      UpdatableResults.substituteVariables(data, variables0, isFirst = true)
    }
  }

  def update(k: String, v: String, last: Boolean): Unit = {

    def updateRef(data: DisplayData, ref: Ref[Map[String, String]]): Unit = {
      val m0 = ref()
      val m  = m0 + (k -> v)
      val data0 =
        UpdatableResults.substituteVariables(data, m, isFirst = false, onlyHighlightOpt = Some(k))
      log.debug(s"Updating variable $k with $v: $data0")
      ref() = m
      Future(updateData(data0))(ec)
      if (last)
        refs.remove(k)
    }

    Option(refs.get(k)) match {
      case None =>
        val r = addRefsLock.synchronized {
          val r = Option(refs.get(k))
          if (r.isEmpty) {
            log.warn(s"Updatable variable $k not found")
            earlyUpdates += k -> (v, last)
          }
          r
        }
        for ((data, ref) <- r)
          updateRef(data, ref)
      case Some((data, ref)) =>
        updateRef(data, ref)
    }
  }

}

object UpdatableResults {

  def substituteVariables(
    d: DisplayData,
    m: Map[String, String],
    isFirst: Boolean,
    onlyHighlightOpt: Option[String] = None
  ): DisplayData =
    d.copy(
      data = d.data.map {
        case ("text/plain", t) =>
          "text/plain" -> m.foldLeft(t) {
            case (acc, (k, v)) =>
              // ideally, we should keep the pprint tree instead of plain text here, for things to get reflowed if
              // needed
              acc.replace(k, v)
          }
        case ("text/html", t) =>
          "text/html" -> m.foldLeft(t) {
            case (acc, (k, v)) =>
              val baos = new ByteArrayOutputStream
              val haos = new HtmlAnsiOutputStream(baos)
              haos.write(v.getBytes(StandardCharsets.UTF_8))
              haos.close()

              val (prefix, suffix) =
                if (isFirst || onlyHighlightOpt.exists(_ != k)) ("", "")
                else (
                  """<style>@keyframes fadein { from { opacity: 0; } to { opacity: 1; } }</style><span style="animation: fadein 2s;">""",
                  "</span>"
                )
              acc.replace(k, prefix + baos.toString("UTF-8") + suffix)
          }
        case kv =>
          kv
      }
    )

}
