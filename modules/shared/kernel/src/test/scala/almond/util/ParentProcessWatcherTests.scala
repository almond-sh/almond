package almond.util

import almond.logger.LoggerContext
import utest._

import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.concurrent.duration.DurationInt
import scala.util.Properties

object ParentProcessWatcherTests extends TestSuite {

  // java.lang.ProcessHandle, that ParentProcessWatcher relies on, only exists from Java 9 on
  private def hasProcessHandle =
    scala.util.Try(Class.forName("java.lang.ProcessHandle")).isSuccess

  // Process#pid only exists from Java 9 on too
  private def pid(p: Process): Long =
    classOf[Process].getMethod("pid").invoke(p).asInstanceOf[java.lang.Long].longValue()

  val tests = Tests {

    test("exit once parent is gone") {
      if (Properties.isWin || !hasProcessHandle) "Disabled"
      else {
        val proc = new ProcessBuilder("sleep", "60").start()
        try {
          val gone = new CountDownLatch(1)
          val threadOpt = ParentProcessWatcher.watch(
            pid(proc),
            LoggerContext.nop,
            period = 50.millis,
            onParentGone = () => gone.countDown()
          )
          assert(threadOpt.nonEmpty)
          assert(!gone.await(500L, TimeUnit.MILLISECONDS))
          proc.destroy()
          proc.waitFor()
          assert(gone.await(10L, TimeUnit.SECONDS))
        }
        finally proc.destroyForcibly()
        ""
      }
    }

    test("exit once parent is gone, via its handle") {
      if (!Properties.isWin || !hasProcessHandle) "Disabled"
      else {
        assert(ParentProcessWatcher.jnaAvailable)
        val proc = new ProcessBuilder("ping", "-n", "60", "127.0.0.1").start()
        try {
          val kernel32 = WindowsProcesses.kernel32
          val handle = kernel32.OpenProcess(
            WindowsProcesses.Synchronize | WindowsProcesses.ProcessQueryLimitedInformation,
            false,
            pid(proc).toInt
          )
          assert(handle != null)
          try {
            val gone = new CountDownLatch(1)
            val threadOpt = ParentProcessWatcher.watchHandle(
              com.sun.jna.Pointer.nativeValue(handle),
              LoggerContext.nop,
              onParentGone = () => gone.countDown()
            )
            assert(threadOpt.nonEmpty)
            assert(!gone.await(500L, TimeUnit.MILLISECONDS))
            proc.destroy()
            proc.waitFor()
            assert(gone.await(10L, TimeUnit.SECONDS))
          }
          finally kernel32.CloseHandle(handle)
        }
        finally proc.destroyForcibly()
        ""
      }
    }

    test("don't watch handles that aren't process ones") {
      if (!Properties.isWin) "Disabled"
      else {
        assert(ParentProcessWatcher.jnaAvailable)
        for (handle <- Seq(0L, 123456789L, -1L /* pseudo-handle of the current process */ )) {
          val threadOpt =
            ParentProcessWatcher.watchHandle(handle, LoggerContext.nop, onParentGone = () => ())
          assert(threadOpt.isEmpty)
        }
        ""
      }
    }

    test("don't watch processes that don't exist") {
      if (Properties.isWin || !hasProcessHandle) "Disabled"
      else {
        val proc = new ProcessBuilder("true").start()
        proc.waitFor()
        val threadOpt =
          ParentProcessWatcher.watch(pid(proc), LoggerContext.nop, onParentGone = () => ())
        assert(threadOpt.isEmpty)
        ""
      }
    }
  }
}
