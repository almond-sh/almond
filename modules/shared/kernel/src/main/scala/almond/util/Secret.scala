package almond.util

import java.util.UUID

sealed abstract class Secret[T] {
  def value: T
}

object Secret {

  private final case class Impl[T](value: T) extends Secret[T] {
    override def toString: String =
      "****"
  }

  def apply[T](value: T): Secret[T] =
    Impl(value)

  def randomUuid(): Secret[String] =
    Secret(UUID.randomUUID().toString)
}
