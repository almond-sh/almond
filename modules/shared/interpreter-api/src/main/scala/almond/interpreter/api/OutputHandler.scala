package almond.interpreter.api

/** Sends output to the Jupyter UI.
  *
  * Can send stdout and stderr messages straightaway to the UI, short-circuiting the
  * [[java.io.PrintStream]]s of [[println]] and the like.
  *
  * Can also send more evolved messages, made of HTML, JS, etc.
  *
  * Instances of [[OutputHandler]] are typically available to users in the implicit scope of code
  * run via the kernel, and can publish stuff in the current cell *while it is running*.
  *
  * If no cell is currently running, no new elements can be pushed to the UI, but previous ones can
  * still be updated.
  */
abstract class OutputHandler extends OutputHandler.Helpers with OutputHandler.UpdateHelpers {

  /** Sends stdout output to the Jupyter UI */
  def stdout(s: String): Unit

  /** Sends stderr output to the Jupyter UI */
  def stderr(s: String): Unit

  /** Sends [[DisplayData]] element to the Jupyter UI.
    *
    * If `idOpt` in the data is non-empty, the corresponding output can be updated (see
    * [[OutputHandler.UpdateDisplay]]).
    */
  def display(displayData: DisplayData): Unit

  def canOutput(): Boolean

  def messageIdOpt: Option[String]
}

object OutputHandler {

  trait UpdateDisplay {

    /** Updates a previously published element.
      *
      * @param displayData:
      *   new content, with non empty idOpt field
      */
    def updateDisplay(displayData: DisplayData): Unit
  }

  abstract class Helpers extends UpdateDisplay {
    def display(displayData: DisplayData): Unit
    final def html(html0: String): Unit =
      display(DisplayData(Map("text/html" -> html0)))
    final def html(html0: String, id: String): Unit =
      display(DisplayData(Map("text/html" -> html0), idOpt = Some(id)))

    final def js(js0: String): Unit =
      display(DisplayData(Map("application/javascript" -> js0)))
    final def js(js0: String, id: String): Unit =
      display(DisplayData(Map("application/javascript" -> js0), idOpt = Some(id)))
  }

  trait UpdateHelpers extends UpdateDisplay {
    final def updateHtml(html0: String, id: String): Unit =
      updateDisplay(DisplayData(Map("text/html" -> html0), idOpt = Some(id)))
  }

  final class OnlyUpdateVia(commHandlerOpt: => Option[CommHandler]) extends OutputHandler {

    private def unsupported() =
      throw new Exception("unsupported (no cell currently running?)")

    def stdout(s: String): Unit =
      unsupported()
    def stderr(s: String): Unit =
      unsupported()
    def display(displayData: DisplayData): Unit =
      unsupported()

    def updateDisplay(displayData: DisplayData): Unit =
      commHandlerOpt match {
        case Some(commHandler) => commHandler.updateDisplay(displayData)
        case None              => unsupported()
      }
    def canOutput(): Boolean =
      false

    def messageIdOpt: Option[String] = None
  }

  final class StableOutputHandler(underlying: => OutputHandler) extends OutputHandler {
    def stdout(s: String): Unit =
      underlying.stdout(s)
    def stderr(s: String): Unit =
      underlying.stderr(s)
    def display(displayData: DisplayData): Unit =
      underlying.display(displayData)
    def updateDisplay(displayData: DisplayData): Unit =
      underlying.updateDisplay(displayData)
    def canOutput(): Boolean =
      underlying.canOutput()

    def messageIdOpt: Option[String] =
      underlying.messageIdOpt
  }

  object NopOutputHandler extends OutputHandler {
    def stdout(s: String): Unit                       = ()
    def stderr(s: String): Unit                       = ()
    def display(displayData: DisplayData): Unit       = ()
    def updateDisplay(displayData: DisplayData): Unit = ()
    def canOutput(): Boolean                          = false

    def messageIdOpt: Option[String] = None
  }

}
