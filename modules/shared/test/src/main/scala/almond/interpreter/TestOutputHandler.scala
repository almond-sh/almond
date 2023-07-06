package almond.interpreter

import almond.interpreter.api.{DisplayData, OutputHandler}

import scala.collection.mutable.ListBuffer

final class TestOutputHandler extends OutputHandler {

  import TestOutputHandler._

  private val output = new ListBuffer[Output]
  private val lock   = new Object

  def stdout(s: String): Unit =
    lock.synchronized {
      output += Output.Stdout(s)
    }

  def stderr(s: String): Unit =
    lock.synchronized {
      output += Output.Stderr(s)
    }

  def display(displayData: DisplayData): Unit =
    lock.synchronized {
      output += Output.Display(displayData)
    }

  def updateDisplay(displayData: DisplayData): Unit =
    lock.synchronized {
      output += Output.UpdateDisplay(displayData)
    }
  def canOutput(): Boolean =
    true

  def result(): Seq[Output] =
    output.result()

  def messageIdOpt: Option[String] = None
}

object TestOutputHandler {

  sealed abstract class Output extends Product with Serializable

  object Output {
    final case class Stdout(s: String)                extends Output
    final case class Stderr(s: String)                extends Output
    final case class Display(data: DisplayData)       extends Output
    final case class UpdateDisplay(data: DisplayData) extends Output
  }

}
