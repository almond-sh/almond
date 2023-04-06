package almond.internals

import java.io.PrintStream
import java.nio.charset.{Charset, StandardCharsets}

final class CaptureImpl(
  inputBufferSize: Int = 1024,
  outputBufferSize: Int = 1024,
  internalCharset: Charset = StandardCharsets.UTF_8
) extends Capture {

  // not thread-safe

  private var out0: String => Unit = _
  private var err0: String => Unit = _

  val out: PrintStream =
    new FunctionOutputStream(
      inputBufferSize,
      outputBufferSize,
      internalCharset,
      s => if (out0 != null) out0(s)
    ).printStream()

  val err: PrintStream =
    new FunctionOutputStream(
      inputBufferSize,
      outputBufferSize,
      internalCharset,
      s => if (err0 != null) err0(s)
    ).printStream()

  def apply[T](
    stdout: String => Unit,
    stderr: String => Unit
  )(
    block: => T
  ): T =
    try {
      out0 = stdout
      err0 = stderr

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
    finally {
      out0 = null
      err0 = null
    }

}
