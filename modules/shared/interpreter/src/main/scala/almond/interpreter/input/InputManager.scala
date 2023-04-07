package almond.interpreter.input

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.ByteBuffer

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

trait InputManager {
  def done(): Unit
  def readInput(prompt: String = "", password: Boolean = false): Future[String]

  final def password(prompt: String = ""): Future[String] =
    readInput(prompt, password = true)

  final def inputStream(ec: ExecutionContext): InputStream =
    new InputManager.InputManagerInputStream(this, ec)
}

object InputManager {

  class NoMoreInputException extends Exception

  class InputManagerInputStream(
    manager: InputManager,
    ec: ExecutionContext
  ) extends InputStream {

    private var bufferOpt = Option.empty[ByteBuffer]
    private var done      = false

    private def maybeFetchNewBuffer(): Option[ByteBuffer] =
      if (done)
        None
      else {
        if (bufferOpt.forall(!_.hasRemaining)) {

          val res = {
            implicit val ec0 = ec
            Await.result(
              manager.readInput()
                .map(Success(_))
                .recover { case t => Failure(t) },
              Duration.Inf
            )
          }

          res match {
            case Success(value) =>
              val b0 = ByteBuffer.wrap((value + "\n").getBytes(UTF_8)).asReadOnlyBuffer()
              bufferOpt = Some(b0)
            case Failure(_: NoMoreInputException) =>
              done = true
              bufferOpt = None
            case Failure(ex) =>
              throw new Exception("Error getting more input", ex)
          }
        }

        bufferOpt
      }

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
  }

}
