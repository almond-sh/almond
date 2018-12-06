package almond.internals

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

  val refs = new ConcurrentHashMap[String, Ref[(DisplayData, Map[String, String])]]

  val addRefsLock = new Object

  val earlyUpdates = new mutable.HashMap[String, (String, Boolean)]

  def add(data: DisplayData, variables: Map[String, String]): DisplayData = {
    val ref = Ref((data, variables))
    addRefsLock.synchronized {
      for (k <- variables.keys)
        refs.put(k, ref)
      val variables0 = variables.map {
        case (k, v) =>
          k -> earlyUpdates.get(k).fold(v)(_._1)
      }
      UpdatableResults.substituteVariables(data, variables0)
    }
  }

  def update(k: String, v: String, last: Boolean): Unit = {

    def updateRef(ref: Ref[(DisplayData, Map[String, String])]): Unit = {
      log.info(s"Updating variable $k with $v")
      val (data0, m0) = ref()
      val m = m0 + (k -> v)
      val data = UpdatableResults.substituteVariables(data0, m)
      ref() = (data, m)
      Future(updateData(data))(ec)
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
        for (ref <- r)
          updateRef(ref)
      case Some(ref) =>
        updateRef(ref)
    }
  }

}

object UpdatableResults {

  def substituteVariables(d: DisplayData, m: Map[String, String]): DisplayData =
    d.copy(
      data = d.data.map {
        case ("text/plain", t) =>
          "text/plain" -> m.foldLeft(t) {
            case (acc, (k, v)) =>
              // ideally, we should keep the pprint tree instead of plain text here, for things to get reflowed if
              // needed
              acc.replace(k, v)
          }
        case kv => kv
      }
    )

}
