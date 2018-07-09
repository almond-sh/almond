package jupyter.scala

// Extracted from IScala, and refactored a bit

import java.io.{ Console => _, _ }
import java.nio.charset.Charset

object Capture {
  private def watchStream(
    input: InputStream,
    fn: String => Unit,
    name: String,
    size: Int = 10240
  ) =
    new Thread(name) {
      override def run() = {
        val buffer = Array.ofDim[Char](size)
        val reader = new InputStreamReader(input, Charset.defaultCharset())

        try {
          while (true) {
            val n = reader.read(buffer, 0, size)
            if (n > 0) fn(new String(buffer take n))
            if (n < size) Thread.sleep(50) // little delay to accumulate output
          }
        } catch {
          case _: IOException =>
          case e: Exception =>
            Console.err.println(s"Unexpected exception in $name thread: $e")
        }
      }
    }

  private def withOut[T](newOut: PrintStream)(block: => T) =
    Console.withOut(newOut) {
      val oldOut = System.out
      System.setOut(newOut)

      try block
      finally System.setOut(oldOut)
    }

  private def withErr[T](newErr: PrintStream)(block: => T) =
    Console.withErr(newErr) {
      val oldErr = System.err
      System.setErr(newErr)

      try block finally System.setErr(oldErr)
    }

  private def withOutAndErr[T](
    outOpt: Option[PipedOutputStream],
    errOpt: Option[PipedOutputStream] )(
    block: => T
  ) = {
    def ps(s: OutputStream) = new PrintStream(s, true)

    try {
      val res =
        (outOpt map ps, errOpt map ps) match {
          case (Some(newOut), Some(newErr)) => withOut(newOut)(withErr(newErr)(block))
          case (None,         Some(newErr)) => withErr(newErr)(block)
          case (Some(newOut), None        ) => withOut(newOut)(block)
          case (None        , None        ) => block
        }

      outOpt.foreach(_.flush())
      errOpt.foreach(_.flush())

      res
    } finally {
      outOpt.foreach(_.close())
      errOpt.foreach(_.close())
    }
  }

  //   Comment from IScala
  // This is a heavyweight solution to start stream watch threads per
  // input, but currently it's the cheapest approach that works well in
  // multiple thread setup. Note that piped streams work only in thread
  // pairs (producer -> consumer) and we start one thread per execution,
  // so technically speaking we have multiple producers, which completely
  // breaks the earlier intuitive approach.

  def pipedInputOpt(
    name: String,
    fOpt: Option[String => Unit]
  ) =
    fOpt.map { f =>
      val in = new PipedInputStream()
      val out = new PipedOutputStream(in)
      val thread = watchStream(in, f, name)
      thread.start()
      (in, out, thread)
    }

  def apply[T](
    stdoutOpt: Option[String => Unit],
    stderrOpt: Option[String => Unit] )(
    block: => T
  ): T = {
    var stdoutInOpt, stderrInOpt = Option.empty[(PipedInputStream, PipedOutputStream, Thread)]

    try {
      stdoutInOpt = pipedInputOpt("capture stdout", stdoutOpt)
      stderrInOpt = pipedInputOpt("capture stderr", stderrOpt)

      val result = withOutAndErr(stdoutInOpt.map(_._2), stderrInOpt.map(_._2))(block)

      while (
        stdoutInOpt.exists(t => t._1.available() > 0 && t._3.isAlive) ||
        stderrInOpt.exists(t => t._1.available() > 0 && t._3.isAlive)
      )
        Thread.sleep(10)

      result
    } finally {
      stdoutInOpt.foreach(_._1.close())
      stderrInOpt.foreach(_._1.close())
      stdoutInOpt.foreach(_._2.close())
      stderrInOpt.foreach(_._2.close())
    }
  }
}
