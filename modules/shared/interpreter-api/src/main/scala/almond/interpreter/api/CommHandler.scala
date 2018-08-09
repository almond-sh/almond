package almond.interpreter.api

/**
  * Kind of message broker between the Jupyter UI and the kernel.
  *
  * Typically available in the implicit scope of user code run via the kernel.
  *
  * Can be used by users to send messages to custom code running in the browser, and receive messages from it.
  */
abstract class CommHandler extends OutputHandler.UpdateDisplay with OutputHandler.UpdateHelpers {
  def registerCommTarget(name: String, target: CommTarget): Unit
  def unregisterCommTarget(name: String): Unit

  def commOpen(targetName: String, id: String, data: String): Unit
  def commMessage(id: String, data: String): Unit
  def commClose(id: String, data: String): Unit
}
