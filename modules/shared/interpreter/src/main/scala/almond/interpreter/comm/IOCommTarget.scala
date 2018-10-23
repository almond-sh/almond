package almond.interpreter.comm

import almond.interpreter.api.CommTarget
import almond.interpreter.util.BetterPrinter
import argonaut.{Json, JsonObject}
import cats.effect.IO
import cats.syntax.apply._

import scala.concurrent.ExecutionContext

trait IOCommTarget {
  def open(id: String, data: JsonObject): IO[Unit]
  def message(id: String, data: JsonObject): IO[Unit]
  def close(id: String, data: JsonObject): IO[Unit]
}

object IOCommTarget {

  def fromCommTarget(commTarget: CommTarget, commEc: ExecutionContext): IOCommTarget =
    new FromCommTarget(commTarget, commEc)

  final class FromCommTarget(commTarget: CommTarget, commEc: ExecutionContext) extends IOCommTarget {

    private def jsonObjToString(obj: JsonObject): String =
      BetterPrinter.noSpaces(Json.jObject(obj))

    def open(id: String, data: JsonObject): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.open(id, jsonObjToString(data)))
    def message(id: String, data: JsonObject): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.message(id, jsonObjToString(data)))
    def close(id: String, data: JsonObject): IO[Unit] =
      IO.shift(commEc) *> IO(commTarget.close(id, jsonObjToString(data)))
  }

}
