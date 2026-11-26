package almond.lsp

import almond.logger.LoggerContext

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Something language features are busy with, that clients can show to users
  *
  * @param id
  *   identifies the task among those in progress
  * @param title
  *   what's being done, like "Indexing"
  * @param message
  *   details, like the current step of the task
  */
final case class LspTask(id: String, title: String, message: Option[String] = None)

/** Tasks in progress, that listeners get once they last for a while
  *
  * Tasks that complete quickly - most requests to Plasmon do - are never reported, which spares
  * clients a status that flickers.
  *
  * @param delay
  *   how long tasks have to be in progress before being reported
  */
final class LspTasks(
  delay: FiniteDuration = 500.millis,
  logCtx: LoggerContext = LoggerContext.nop
) extends AutoCloseable {

  private val log = logCtx(getClass)

  private val lock = new Object

  /** Tasks in progress, in the order they started, with the number of their start */
  private var started = Vector.empty[(LspTask, Long)]

  /** Identifiers of the tasks of [[started]] that are reported */
  private var visible = Set.empty[String]

  private var startCount = 0L

  private var listeners = Vector.empty[Seq[LspTask] => Unit]

  private lazy val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { (r: Runnable) =>
      val t = new Thread(r, "lsp-tasks")
      t.setDaemon(true)
      t
    }

  /** The reported tasks */
  def current: Seq[LspTask] =
    lock.synchronized {
      started.collect { case (task, _) if visible(task.id) => task }
    }

  /** Starts `task`, or updates it if a task with the same identifier is in progress */
  def start(task: LspTask): Unit = {
    val revealOpt = lock.synchronized {
      started.indexWhere(_._1.id == task.id) match {
        case -1 =>
          startCount += 1
          started = started :+ (task -> startCount)
          Some(startCount)
        case idx =>
          val (former, count) = started(idx)
          if (former != task) {
            started = started.updated(idx, task -> count)
            if (visible(task.id))
              notifyListeners()
          }
          None
      }
    }
    for (count <- revealOpt)
      scheduler.schedule(
        new Runnable {
          def run(): Unit = reveal(task.id, count)
        },
        delay.toMillis,
        TimeUnit.MILLISECONDS
      )
  }

  def done(id: String): Unit =
    lock.synchronized {
      if (started.exists(_._1.id == id)) {
        started = started.filter(_._1.id != id)
        if (visible(id)) {
          visible -= id
          notifyListeners()
        }
      }
    }

  /** Runs `f` as `task` */
  def apply[T](task: LspTask)(f: => T): T = {
    start(task)
    try f
    finally done(task.id)
  }

  /** Calls `listener` with the reported tasks, now and whenever they change, until the returned
    * value is closed
    */
  def subscribe(listener: Seq[LspTask] => Unit): AutoCloseable = {
    lock.synchronized {
      listeners = listeners :+ listener
      call(listener)
    }
    () =>
      lock.synchronized {
        listeners = listeners.filter(_ ne listener)
      }
  }

  def close(): Unit =
    scheduler.shutdownNow()

  private def reveal(id: String, count: Long): Unit =
    lock.synchronized {
      if (started.exists { case (task, count0) => task.id == id && count0 == count }) {
        visible += id
        notifyListeners()
      }
    }

  // called with lock held, so that listeners get changes in order
  private def notifyListeners(): Unit =
    listeners.foreach(call)

  private def call(listener: Seq[LspTask] => Unit): Unit =
    try listener(current)
    catch {
      case NonFatal(e) =>
        log.warn("Error notifying of LSP tasks", e)
    }
}
