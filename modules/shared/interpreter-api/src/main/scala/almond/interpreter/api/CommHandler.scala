package almond.interpreter.api

/**
  * Kind of message broker between the Jupyter UI and the kernel.
  *
  * See https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#custom-messages.
  *
  * Typically available in the implicit scope of user code run via the kernel.
  *
  * Can be used by users to send messages to custom code running in the browser, and receive messages from it.
  *
  * Registering a target with name `"target_name"` allows to receive messages from frontends.
  * From the Jupyter classic UI, one can send messages to this target via code like
  * {{{
  *   var comm = Jupyter.notebook.kernel.comm_manager.new_comm("target_name", '{"a": 2, "b": false}');
  *   comm.open();
  *   comm.send('{"c": 2, "d": {"foo": [1, 2]}}');
  * }}}
  */
abstract class CommHandler extends OutputHandler.UpdateDisplay with OutputHandler.UpdateHelpers {
  def registerCommTarget(name: String, target: CommTarget): Unit
  def unregisterCommTarget(name: String): Unit

  def commOpen(targetName: String, id: String, data: String): Unit
  def commMessage(id: String, data: String): Unit
  def commClose(id: String, data: String): Unit
}
