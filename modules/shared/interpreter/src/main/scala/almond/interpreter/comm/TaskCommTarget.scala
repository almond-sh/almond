package almond.interpreter.comm

import almond.interpreter.api.CommTarget
import argonaut.{Json, JsonObject}
import cats.effect.IO
import cats.syntax.apply._

import scala.concurrent.ExecutionContext

trait TaskCommTarget {
  def open(id: String, data: JsonObject): IO[Unit]
  def message(id: String, data: JsonObject): IO[Unit]
  def close(id: String, data: JsonObject): IO[Unit]
}

object TaskCommTarget {

  def fromCommTarget(commTarget: CommTarget, commEc: ExecutionContext): TaskCommTarget =
    new FromCommTarget(commTarget, commEc)

  final class FromCommTarget(commTarget: CommTarget, commEc: ExecutionContext) extends TaskCommTarget {

    private def jsonObjToString(obj: JsonObject): String =
      Json.jObject(obj).nospaces

    def open(id: String, data: JsonObject): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.open(id, jsonObjToString(data)))
    def message(id: String, data: JsonObject): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.message(id, jsonObjToString(data)))
    def close(id: String, data: JsonObject): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.close(id, jsonObjToString(data)))
  }

}
