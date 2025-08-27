package almondbuild.modules

import mill._

import java.io.File

import scala.annotation.tailrec

object Util {
  def withLoader[T](loader: ClassLoader)(f: => T): T = {
    val thread = Thread.currentThread()
    val cl     = thread.getContextClassLoader
    try {
      thread.setContextClassLoader(loader)
      f
    }
    finally thread.setContextClassLoader(cl)
  }

  def run(cmd: Seq[String], dir: File = null) = {
    val b = new ProcessBuilder(cmd: _*)
    b.inheritIO()
    for (d <- Option(dir))
      b.directory(d)
    System.err.println(s"Running ${cmd.mkString(" ")}")
    val p       = b.start()
    val retCode = p.waitFor()
    if (retCode != 0)
      sys.error(s"Error running ${cmd.mkString(" ")} (return code: $retCode)")
  }

  def withBgProcess[T](
    cmd: Seq[String],
    dir: File = new File("."),
    waitFor: () => Unit = null
  )(f: => T): T = {

    val b = new ProcessBuilder(cmd: _*)
    b.inheritIO()
    b.directory(dir)
    var p: Process = null

    Option(waitFor) match {
      case Some(w) =>
        val t = new Thread("wait-for-condition") {
          setDaemon(true)
          override def run() = {
            w()
            System.err.println(s"Running ${cmd.mkString(" ")}")
            p = b.start()
          }
        }
        t.start()
      case None =>
        System.err.println(s"Running ${cmd.mkString(" ")}")
        p = b.start()
    }

    try f
    finally {
      p.destroy()
      p.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS)
      p.destroyForcibly()
    }
  }

  def waitForDir(dir: File): Unit = {
    @tailrec
    def helper(): Unit = {
      val found =
        dir.exists() && {
          assert(dir.isDirectory)
          dir.listFiles().nonEmpty
        }

      if (!found) {
        Thread.sleep(200L)
        helper()
      }
    }

    helper()
  }
}
