package almond.internals

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit}

import almond.interpreter.api.DisplayData
import almond.logger.LoggerContext
import almond.util.ThreadUtil
import ammonite.util.Ref

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

/** Keeps track of displayed outputs that can be updated later on, and sends updates for them
  *
  * If `minUpdateInterval` is non-empty, updates are coalesced: when many updates are sent for the
  * same key in a short amount of time (like when a var is modified in a loop), only the latest
  * value is actually sent to the front-end. At most one batch of updates is sent per
  * `minUpdateInterval` (the first one being sent right away), and [[flush]] allows to send pending
  * updates immediately (like at the end of a cell). Values passed to [[update]] are then computed
  * lazily, when the update is actually sent, and outside of the thread that called [[update]]
  * (unless [[flush]] is called from it).
  *
  * If `minUpdateInterval` is empty, each update is computed right away, from the thread calling
  * [[update]], and sent straightaway via `ec`.
  */
final class UpdatableResults(
  ec: ExecutionContext,
  logCtx: LoggerContext,
  updateData: DisplayData => Unit,
  minUpdateInterval: Option[FiniteDuration]
) {

  def this(
    ec: ExecutionContext,
    logCtx: LoggerContext,
    updateData: DisplayData => Unit
  ) =
    this(ec, logCtx, updateData, Some(UpdatableResults.defaultMinUpdateInterval))

  private val log = logCtx(getClass)

  val refs = new ConcurrentHashMap[String, (DisplayData, Ref[Map[String, String]])]

  val addRefsLock = new Object

  val earlyUpdates = new mutable.HashMap[String, (String, Boolean)]

  // Guards pending, flushScheduled, lastFlushNanos
  // (lock order: flushLock, then pendingLock, never the other way around)
  private val pendingLock    = new Object
  private val pending        = new mutable.LinkedHashMap[String, UpdatableResults.PendingUpdate]
  private var flushScheduled = false
  private var lastFlushNanos = Option.empty[Long]

  // Held while computing and sending updates, so that updates for a given key
  // are sent in the order they were taken out of pending
  private val flushLock = new Object

  private val minUpdateIntervalNanosOpt = minUpdateInterval.map(_.toNanos max 0L)

  def add(data: DisplayData, variables: Map[String, String]): DisplayData =
    flushLock.synchronized {
      // Send the updates that might have been received for these variables
      // (they end up in earlyUpdates, as their refs aren't known yet)
      flushNow(fromScheduledFlush = false)
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

  /** Registers an update for key `k`
    *
    * If updates are coalesced, `v` is computed later on, when the update is actually sent. If other
    * updates for `k` are registered before that, only the latest one is sent.
    *
    * Else, `v` is computed right away, and the update is sent straightaway.
    */
  def update(k: String, v: => String, last: Boolean): Unit =
    minUpdateIntervalNanosOpt match {
      case None =>
        doUpdate(
          k,
          v,
          last,
          send = data =>
            ec.execute(
              new Runnable {
                def run(): Unit =
                  updateData(data)
              }
            )
        )
      case Some(minUpdateIntervalNanos) =>
        coalescedUpdate(k, () => v, last, minUpdateIntervalNanos)
    }

  private def coalescedUpdate(
    k: String,
    v0: () => String,
    last: Boolean,
    minUpdateIntervalNanos: Long
  ): Unit = {
    val scheduleDelayNanosOpt = pendingLock.synchronized {
      val last0 = last || pending.get(k).exists(_.last)
      pending.put(k, UpdatableResults.PendingUpdate(v0, last0))
      if (flushScheduled) None
      else {
        flushScheduled = true
        val delayNanos = lastFlushNanos.fold(0L) { t =>
          minUpdateIntervalNanos - (System.nanoTime() - t)
        }
        Some(delayNanos)
      }
    }
    // Not holding pendingLock here, as ec might run flushTask right away, from this thread
    for (delayNanos <- scheduleDelayNanosOpt)
      if (delayNanos <= 0L)
        ec.execute(flushTask)
      else
        UpdatableResults.scheduler.schedule(
          new Runnable {
            def run(): Unit =
              ec.execute(flushTask)
          },
          delayNanos,
          TimeUnit.NANOSECONDS
        )
  }

  /** Sends all pending updates right away, from the calling thread */
  def flush(): Unit =
    flushLock.synchronized {
      flushNow(fromScheduledFlush = false)
    }

  private val flushTask: Runnable =
    new Runnable {
      def run(): Unit =
        flushLock.synchronized {
          flushNow(fromScheduledFlush = true)
        }
    }

  // Lock order: flushLock, then pendingLock (never the other way around)
  // Must be called with flushLock held.
  private def flushNow(fromScheduledFlush: Boolean): Unit = {
    val toSend = pendingLock.synchronized {
      if (fromScheduledFlush)
        flushScheduled = false
      if (pending.isEmpty) Nil
      else {
        lastFlushNanos = Some(System.nanoTime())
        val l = pending.toList
        pending.clear()
        l
      }
    }
    for ((k, p) <- toSend) {
      val valueOpt =
        try Some(p.value())
        catch {
          case NonFatal(e) =>
            log.warn(s"Error computing new value of $k, ignoring it", e)
            None
        }
      for (v <- valueOpt)
        try doUpdate(k, v, p.last, send = updateData)
        catch {
          case NonFatal(e) =>
            log.warn(s"Error sending update of $k", e)
        }
    }
  }

  private def doUpdate(
    k: String,
    v: String,
    last: Boolean,
    send: DisplayData => Unit
  ): Unit = {

    def updateRef(data: DisplayData, ref: Ref[Map[String, String]]): Unit = {
      val m0 = ref()
      val m  = m0 + (k -> v)
      val data0 =
        UpdatableResults.substituteVariables(data, m, isFirst = false, onlyHighlightOpt = Some(k))
      log.debug(s"Updating variable $k with $v: $data0")
      ref() = m
      send(data0)
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

  private final case class PendingUpdate(value: () => String, last: Boolean)

  /** Minimum amount of time between two batches of updates
    *
    * Front-ends (and the Jupyter server, that rate-limits messages) can't keep up with thousands of
    * updates per second, and each update re-renders the corresponding output.
    */
  val defaultMinUpdateInterval: FiniteDuration = 100.millis

  // Only used to wait before handing flushes over to the updates execution context
  private lazy val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(
      ThreadUtil.daemonThreadFactory("almond-updatable-results-scheduler")
    )

  def substituteVariables(
    d: DisplayData,
    m: Map[String, String],
    isFirst: Boolean,
    onlyHighlightOpt: Option[String] = None
  ): DisplayData =
    d.withDetailedData(
      d.detailedData.map {
        case ("text/plain", DisplayData.Value.String(t)) =>
          val updatedValue = m.foldLeft(t) {
            case (acc, (k, v)) =>
              // ideally, we should keep the pprint tree instead of plain text here, for things to get reflowed if
              // needed
              acc.replace(k, v)
          }
          "text/plain" -> DisplayData.Value.String(updatedValue)
        case ("text/html", DisplayData.Value.String(t)) =>
          val updatedValue = m.foldLeft(t) {
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
          "text/html" -> DisplayData.Value.String(updatedValue)
        case kv =>
          kv
      }
    )

}
