package almond.util

import almond.logger.LoggerContext

import java.lang.reflect.Method
import java.util.Optional

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Properties
import scala.util.control.NonFatal

/** Exits the kernel once the Jupyter client that started it is gone.
  *
  * On Linux and macOS, `jupyter_client` passes its PID to the kernels it starts, via the
  * `JPY_PARENT_PID` environment variable. If the client dies without shutting its kernels down
  * (killed, crashed, …), they would otherwise run forever, possibly keeping a cell running. Like
  * ipykernel, we watch that process, and exit once it's gone.
  *
  * This relies on `java.lang.ProcessHandle`, that only exists from Java 9 on, and that we access
  * via reflection (our modules target Java 8). On Java 8, parents aren't watched.
  */
object ParentProcessWatcher {

  def envVar: String = "JPY_PARENT_PID"

  /** PID of the client process that started us, if it passed it to us
    *
    * On Windows, `jupyter_client` puts a handle rather than a PID in `JPY_PARENT_PID`, so this is
    * always empty there.
    */
  def parentPidFromEnv(): Option[Long] =
    if (Properties.isWin) None
    else
      Option(System.getenv(envVar))
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap(s => scala.util.Try(s.toLong).toOption)
        .filter(_ > 1L) // PID 1 is init, our parent once the client is gone

  /** Starts a daemon thread that exits the JVM once the process with the passed PID is gone
    *
    * Does nothing if that process is already gone, or if the JVM doesn't provide
    * `java.lang.ProcessHandle` (Java 8).
    *
    * @return
    *   the watcher thread, if one was started
    */
  def watch(
    pid: Long,
    logCtx: LoggerContext,
    period: FiniteDuration = 1.second,
    onParentGone: () => Unit = () => sys.exit(1)
  ): Option[Thread] = {
    val log = logCtx(getClass)
    ProcessHandles.instanceOpt match {
      case None =>
        log.debug(s"java.lang.ProcessHandle not available, not watching parent process $pid")
        None
      case Some(handles) =>
        handles.startInstant(pid) match {
          case None =>
            log.warn(
              s"Parent process $pid (from $envVar) not found, not watching it"
            )
            None
          case Some(startInstant) =>
            log.debug(s"Watching parent process $pid")
            // The start instant of the process guards against PID reuse
            def parentAlive(): Boolean =
              handles.startInstant(pid).contains(startInstant)
            val t: Thread =
              new Thread(s"watch-parent-$pid") {
                setDaemon(true)
                override def run(): Unit = {
                  try
                    while (parentAlive())
                      Thread.sleep(period.toMillis)
                  catch {
                    case _: InterruptedException =>
                      return
                  }
                  log.warn(s"Parent process $pid is gone, exiting")
                  onParentGone()
                }
              }
            t.start()
            Some(t)
        }
    }
  }

  /** Watches the process whose PID is in `JPY_PARENT_PID`, if any (see [[watch]]) */
  def watchFromEnv(logCtx: LoggerContext): Option[Thread] =
    parentPidFromEnv().flatMap(watch(_, logCtx))

  private final class ProcessHandles(
    of: Method,
    isAlive: Method,
    info: Method,
    infoStartInstant: Method
  ) {

    /** Start instant of a live process (its PID if the JVM can't get it), or None if it's gone */
    def startInstant(pid: Long): Option[Any] = {
      val handleOpt = of.invoke(null, java.lang.Long.valueOf(pid)).asInstanceOf[Optional[AnyRef]]
      if (handleOpt.isPresent) {
        val handle = handleOpt.get()
        if (isAlive.invoke(handle).asInstanceOf[Boolean]) {
          val startOpt = infoStartInstant
            .invoke(info.invoke(handle))
            .asInstanceOf[Optional[AnyRef]]
          Some(if (startOpt.isPresent) startOpt.get() else java.lang.Long.valueOf(pid))
        }
        else None
      }
      else None
    }
  }

  private object ProcessHandles {
    lazy val instanceOpt: Option[ProcessHandles] =
      try {
        val cls     = Class.forName("java.lang.ProcessHandle")
        val infoCls = Class.forName("java.lang.ProcessHandle$Info")
        Some(
          new ProcessHandles(
            cls.getMethod("of", java.lang.Long.TYPE),
            cls.getMethod("isAlive"),
            cls.getMethod("info"),
            infoCls.getMethod("startInstant")
          )
        )
      }
      catch {
        case _: ClassNotFoundException | _: NoSuchMethodException =>
          None
        case NonFatal(_) =>
          None
      }
  }
}
