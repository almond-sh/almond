package almond.internals

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8

import scala.annotation.tailrec

class FunctionInputStream(internalCharset: Charset, f: => Option[String]) extends InputStream {

  // not thread-safe

  private var bufferOpt = Option.empty[ByteBuffer]
  private var done      = false

  @tailrec
  private def maybeFetchNewBuffer(): Option[ByteBuffer] =
    if (done)
      None
    else if (bufferOpt.forall(!_.hasRemaining)) {

      val s = f

      s match {
        case Some(value) =>
          val b0 = ByteBuffer.wrap(value.getBytes(UTF_8)).asReadOnlyBuffer()
          bufferOpt = Some(b0)
        case None =>
          done = true
          bufferOpt = None
      }

      maybeFetchNewBuffer() // maybe we were given an empty string, and need to call f again
    }
    else
      bufferOpt

  def read(): Int =
    maybeFetchNewBuffer()
      .fold(-1)(_.get())

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    // InputStream.read does these 3 checks upfront too
    if (b == null)
      throw new NullPointerException
    else if (off < 0 || len < 0 || len > b.length - off)
      throw new IndexOutOfBoundsException
    else if (len == 0)
      0
    else
      maybeFetchNewBuffer().fold(-1) { b0 =>
        val toRead = math.min(b0.remaining(), len)
        b0.get(b, off, toRead)
        toRead
      }

  def clear(): Unit = {
    done = false
    bufferOpt = None
  }
}
