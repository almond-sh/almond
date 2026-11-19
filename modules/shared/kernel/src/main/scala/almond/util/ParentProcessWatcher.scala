package almond.util

import almond.logger.LoggerContext

import java.lang.reflect.Method
import java.util.Optional

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Properties
import scala.util.control.NonFatal

/** Exits the kernel once the Jupyter client that started it is gone.
  *
  * `jupyter_client` tells the kernels it starts who it is, via the `JPY_PARENT_PID` environment
  * variable (unless they're "independent"). If the client dies without shutting its kernels down
  * (killed, crashed, …), they would otherwise run forever, possibly keeping a cell running. Like
  * ipykernel, we watch the client, and exit once it's gone.
  *
  * On Linux and macOS, `JPY_PARENT_PID` contains the PID of the client. We poll that process with
  * `java.lang.ProcessHandle`, that only exists from Java 9 on, and that we access via reflection
  * (our modules target Java 8). On Java 8, the client isn't watched.
  *
  * On Windows, `JPY_PARENT_PID` contains a handle of the client process, that we inherited. If JNA
  * is on the class path (launcher), we wait for that process via its handle. Else (kernel, that
  * doesn't depend on JNA), we watch our parent process instead: the client if it started us
  * directly, the launcher if the launcher started us. The launcher shuts the kernel down when it
  * exits itself.
  */
object ParentProcessWatcher {

  def envVar: String = "JPY_PARENT_PID"

  private def defaultOnParentGone(): Unit =
    sys.exit(1)

  /** PID of the client process that started us, if it passed it to us
    *
    * On Windows, `jupyter_client` puts a handle rather than a PID in `JPY_PARENT_PID`, so this is
    * always empty there.
    */
  def parentPidFromEnv(): Option[Long] =
    if (Properties.isWin) None
    else
      envValue()
        .flatMap(s => scala.util.Try(s.toLong).toOption)
        .filter(_ > 1L) // PID 1 is init, our parent once the client is gone

  private def envValue(): Option[String] =
    Option(System.getenv(envVar))
      .map(_.trim)
      .filter(_.nonEmpty)

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
    onParentGone: () => Unit = () => defaultOnParentGone()
  ): Option[Thread] = {
    val log = logCtx(getClass)
    ProcessHandles.instanceOpt match {
      case None =>
        log.debug(s"java.lang.ProcessHandle not available, not watching parent process $pid")
        None
      case Some(handles) =>
        handles.startInstant(pid) match {
          case None =>
            log.warn(s"Parent process $pid not found, not watching it")
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

  /** Whether JNA can be used, so that we can watch Windows processes via their handles */
  lazy val jnaAvailable: Boolean =
    try {
      Class.forName("com.sun.jna.Native", false, getClass.getClassLoader)
      WindowsProcesses.kernel32
      true
    }
    catch {
      case _: ClassNotFoundException | _: LinkageError =>
        false
      case NonFatal(_) =>
        false
    }

  /** Starts a daemon thread that exits the JVM once the process the passed handle refers to is gone
    *
    * Windows only, and needs JNA (see [[jnaAvailable]]). Does nothing if JNA isn't available, or if
    * the handle doesn't refer to another live process.
    */
  def watchHandle(
    handle: Long,
    logCtx: LoggerContext,
    onParentGone: () => Unit = () => defaultOnParentGone()
  ): Option[Thread] =
    if (Properties.isWin && jnaAvailable)
      WindowsProcesses.watch(handle, logCtx, onParentGone)
    else
      None

  /** Like [[watch]], for our current parent process */
  def watchParent(logCtx: LoggerContext): Option[Thread] =
    ProcessHandles.instanceOpt.flatMap(_.parentPid()) match {
      case Some(pid) => watch(pid, logCtx)
      case None =>
        logCtx(getClass).debug("Parent process not found, not watching it")
        None
    }

  /** Watches the Jupyter client that started us, if it passed us `JPY_PARENT_PID` (see the scaladoc
    * of [[ParentProcessWatcher]])
    */
  def watchFromEnv(logCtx: LoggerContext): Option[Thread] =
    if (Properties.isWin)
      envValue().flatMap { value =>
        if (jnaAvailable)
          scala.util.Try(value.toLong).toOption match {
            case Some(handle) => watchHandle(handle, logCtx)
            case None =>
              logCtx(getClass).warn(s"Malformed $envVar value: '$value', not watching parent")
              None
          }
        else
          watchParent(logCtx)
      }
    else
      parentPidFromEnv().flatMap(watch(_, logCtx))

  private final class ProcessHandles(
    current: Method,
    parent: Method,
    pid: Method,
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

    def parentPid(): Option[Long] = {
      val parentOpt = parent.invoke(current.invoke(null)).asInstanceOf[Optional[AnyRef]]
      if (parentOpt.isPresent)
        Some(pid.invoke(parentOpt.get()).asInstanceOf[java.lang.Long].longValue())
      else
        None
    }
  }

  private object ProcessHandles {
    lazy val instanceOpt: Option[ProcessHandles] =
      try {
        val cls     = Class.forName("java.lang.ProcessHandle")
        val infoCls = Class.forName("java.lang.ProcessHandle$Info")
        Some(
          new ProcessHandles(
            cls.getMethod("current"),
            cls.getMethod("parent"),
            cls.getMethod("pid"),
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
