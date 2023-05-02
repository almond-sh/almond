package almond.internals

import java.io.PrintStream

final class NopCapture extends Capture {
  val out: PrintStream =
    new NopOutputStream().printStream()
  val err: PrintStream =
    new NopOutputStream().printStream()

  def apply[T](stdout: String => Unit, stderr: String => Unit)(block: => T): T =
    Console.withOut(out) {
      Console.withErr(err) {
        val oldOut = System.out
        val oldErr = System.err

        try {
          System.setOut(out)
          System.setErr(err)

          block
        }
        finally {
          System.setOut(oldOut)
          System.setErr(oldErr)
        }
      }
    }
}
