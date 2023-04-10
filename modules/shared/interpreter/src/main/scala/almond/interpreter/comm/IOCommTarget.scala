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
      IO(commTarget.open(id, data)).evalOn(commEc)
    def message(id: String, data: Array[Byte]): IO[Unit] =
      IO(commTarget.message(id, data)).evalOn(commEc)
    def close(id: String, data: Array[Byte]): IO[Unit] =
      IO(commTarget.close(id, data)).evalOn(commEc)
  }

}
