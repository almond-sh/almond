package almond.internals

import java.util.concurrent.ConcurrentHashMap

import almond.interpreter.api.{DisplayData, OutputHandler}

/** Keeps track of the display ids of the elements displayed in the output of a cell
  *
  * Allows not to display again, at the end of a cell, an updatable display that the cell already
  * displayed, like `bar` in
  * {{{
  *   val bar = ProgressBar(0, 10)
  *   bar.display()
  *   for (i <- 1 to 10) bar.withProgress(i).update()
  * }}}
  */
final class DisplayIdTracker {

  private val ids = ConcurrentHashMap.newKeySet[String]()

  def wasDisplayed(id: String): Boolean =
    ids.contains(id)

  /** Wraps `underlying`, recording the ids of what's displayed through it */
  def wrap(underlying: OutputHandler): OutputHandler =
    new DisplayIdTracker.TrackingOutputHandler(underlying, this)

  private def displayed(data: DisplayData): Unit =
    data.idOpt.foreach(ids.add)

  private def cleared(): Unit =
    // what was displayed is gone (or about to be), so it can be displayed again
    ids.clear()
}

object DisplayIdTracker {

  private final class TrackingOutputHandler(underlying: OutputHandler, tracker: DisplayIdTracker)
      extends OutputHandler {
    def stdout(s: String): Unit =
      underlying.stdout(s)
    def stderr(s: String): Unit =
      underlying.stderr(s)
    def display(displayData: DisplayData): Unit = {
      underlying.display(displayData)
      tracker.displayed(displayData)
    }
    def clearOutput(waitForNewOutput: Boolean): Unit = {
      underlying.clearOutput(waitForNewOutput)
      tracker.cleared()
    }
    def updateDisplay(displayData: DisplayData): Unit =
      underlying.updateDisplay(displayData)
    def canOutput(): Boolean =
      underlying.canOutput()
    def messageIdOpt: Option[String] =
      underlying.messageIdOpt
    def addPayload(payload: String): Unit =
      underlying.addPayload(payload)
  }
}
