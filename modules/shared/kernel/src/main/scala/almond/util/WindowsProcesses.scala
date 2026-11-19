package almond.util

import almond.logger.LoggerContext
import com.sun.jna.{Library, Native, Pointer}

/** Watches Windows processes via their handles, by calling Win32 functions with JNA.
  *
  * JNA is only a compile-time dependency of this module: the launcher has it on its class path, but
  * the kernel doesn't, as it could clash with the JNA version of users. So this must only be used
  * if [[ParentProcessWatcher.jnaAvailable]] is true.
  */
private[almond] object WindowsProcesses {

  trait Kernel32 extends Library {
    def WaitForSingleObject(handle: Pointer, milliseconds: Int): Int
    def GetProcessId(process: Pointer): Int
    def GetCurrentProcessId(): Int
    def OpenProcess(desiredAccess: Int, inheritHandle: Boolean, processId: Int): Pointer
    def CloseHandle(handle: Pointer): Boolean
  }

  // values from the Win32 API
  private def WaitObject0: Int = 0
  private def WaitTimeout: Int = 0x102
  private def Infinite: Int    = 0xffffffff

  def Synchronize: Int                    = 0x00100000
  def ProcessQueryLimitedInformation: Int = 0x1000

  lazy val kernel32: Kernel32 = Native.load("kernel32", classOf[Kernel32])

  /** Starts a daemon thread that calls `onGone` once the process the passed handle refers to exits
    *
    * Does nothing if the handle doesn't refer to another live process.
    *
    * @param handle
    *   handle of a process, valid in the current process
    * @return
    *   the watcher thread, if one was started
    */
  def watch(
    handle: Long,
    logCtx: LoggerContext,
    onGone: () => Unit
  ): Option[Thread] = {
    val log = logCtx(getClass)
    val h   = new Pointer(handle)
    // 0 if the handle isn't valid, or doesn't refer to a process
    val pid = kernel32.GetProcessId(h)
    if (pid == 0) {
      log.warn(s"Handle $handle isn't a process handle, not watching it")
      None
    }
    else if (pid == kernel32.GetCurrentProcessId()) {
      log.warn(s"Handle $handle refers to the current process, not watching it")
      None
    }
    else {
      val res = kernel32.WaitForSingleObject(h, 0)
      if (res == WaitTimeout) {
        log.debug(s"Watching process $pid (handle $handle)")
        val t: Thread =
          new Thread(s"watch-parent-$pid") {
            setDaemon(true)
            override def run(): Unit = {
              val res0 = kernel32.WaitForSingleObject(h, Infinite)
              if (res0 == WaitObject0) {
                log.warn(s"Parent process $pid is gone, exiting")
                onGone()
              }
              else
                log.warn(
                  s"Error waiting for process $pid (WaitForSingleObject returned $res0), not watching it anymore"
                )
            }
          }
        t.start()
        Some(t)
      }
      else {
        if (res == WaitObject0)
          log.warn(s"Process $pid (handle $handle) already exited, not watching it")
        else
          log.warn(
            s"Can't wait for process $pid (handle $handle, WaitForSingleObject returned $res), not watching it"
          )
        None
      }
    }
  }
}
