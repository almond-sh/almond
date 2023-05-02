package almond.interpreter.comm

import almond.interpreter.api.CommTarget
import cats.effect.IO
import cats.syntax.apply._

import scala.concurrent.ExecutionContext

trait IOCommTarget {
  def open(id: String, data: Array[Byte]): IO[Unit]
  def message(id: String, data: Array[Byte]): IO[Unit]
  def close(id: String, data: Array[Byte]): IO[Unit]
}

object IOCommTarget {

  def fromCommTarget(commTarget: CommTarget, commEc: ExecutionContext): IOCommTarget =
    new FromCommTarget(commTarget, commEc)

  final class FromCommTarget(commTarget: CommTarget, commEc: ExecutionContext)
      extends IOCommTarget {

    def open(id: String, data: Array[Byte]): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.open(id, data))
    def message(id: String, data: Array[Byte]): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.message(id, data))
    def close(id: String, data: Array[Byte]): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.close(id, data))
  }

}
