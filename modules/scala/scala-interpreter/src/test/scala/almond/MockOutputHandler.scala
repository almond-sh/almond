package almond

import java.util.concurrent.ConcurrentLinkedQueue

import almond.interpreter.api.{DisplayData, OutputHandler}

class MockOutputHandler extends OutputHandler {

  private var displayed0 = new ConcurrentLinkedQueue[DisplayData]

  def stdout(s: String): Unit = ()

  def stderr(s: String): Unit = ()

  def display(displayData: DisplayData): Unit =
    displayed0.add(displayData)

  def displayed(): Seq[DisplayData] = {
    val b                 = Vector.newBuilder[DisplayData]
    var elem: DisplayData = null
    while ({ elem = displayed0.poll(); elem != null })
      b += elem
    b.result()
  }

  def updateDisplay(displayData: almond.interpreter.api.DisplayData): Unit = ()
  def canOutput(): Boolean                                                 = false
  def messageIdOpt: Option[String]                                         = None
}
