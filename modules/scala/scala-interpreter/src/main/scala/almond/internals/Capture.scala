package almond.internals

import java.io.PrintStream

trait Capture {
  def out: PrintStream
  def err: PrintStream

  def apply[T](stdout: String => Unit, stderr: String => Unit)(block: => T): T
}

object Capture {
  def create(): Capture =
    new CaptureImpl
  def nop(): Capture =
    new NopCapture
}
