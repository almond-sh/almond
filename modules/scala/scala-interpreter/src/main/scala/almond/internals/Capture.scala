package almond.internals

import java.io.PrintStream

trait Capture {
  def out: PrintStream
  def err: PrintStream

  def apply[T](stdout: String => Unit, stderr: String => Unit)(block: => T): T
}

object Capture {
  def create(mirrorToConsole: Boolean): Capture =
    new CaptureImpl(mirrorToConsole = mirrorToConsole)
  def nop(): Capture =
    new NopCapture
}
