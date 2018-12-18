package almond.internals

import java.io.{OutputStream, PrintStream}

final class NopOutputStream extends OutputStream {

  def write(b: Int): Unit =
    ()

  override def write(b: Array[Byte], off: Int, len: Int) =
    ()

  def printStream(): PrintStream =
    new PrintStream(this)

}
