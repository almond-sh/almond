package almond.interpreter.api

import java.util.UUID

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
abstract class CommHandler extends OutputHandler.UpdateHelpers {

  import CommHandler.Comm

  def registerCommTarget(name: String, target: CommTarget): Unit
  def unregisterCommTarget(name: String): Unit
  def registerCommId(id: String, target: CommTarget): Unit
  def unregisterCommId(id: String): Unit

  @throws(classOf[IllegalArgumentException])
  def commOpen(targetName: String, id: String, data: String, metadata: String): Unit
  @throws(classOf[IllegalArgumentException])
  def commMessage(id: String, data: String, metadata: String): Unit
  @throws(classOf[IllegalArgumentException])
  def commClose(id: String, data: String, metadata: String): Unit


  final def receiver(
    name: String,
    onOpen: (String, String) => Unit = (_, _) => (),
    onClose: (String, String) => Unit = (_, _) => ()
  )(
    // scraps the id (first arg of onOpen / onClose)
    onMessage: String => Unit
  ): Unit = {
    val t = CommTarget(
      (_, data) => onMessage(data),
      (id, data) => onOpen(id, data),
      (id, data) => onClose(id, data)
    )
    registerCommTarget(name, t)
  }

  final def sender(
    targetName: String,
    id: String = UUID.randomUUID().toString,
    data: String = "{}",
    metadata: String = "{}",
    onMessage: (String, String) => Unit = (_, _) => (),
    onClose: (String, String) => Unit = (_, _) => ()
  ): Comm = {
    commOpen(targetName, id, data, metadata)
    val t = CommTarget(
      onMessage = (id, data) => onMessage(id, data),
      onOpen = (_, _) => (), // ignore since we open the comm from the kernel
      onClose = (id, data) => onClose(id, data)
    )
    registerCommId(id, t)
    new Comm {
      def message(data: String, metadata: String = "{}") =
        commMessage(id, data, metadata)
      def close(data: String, metadata: String = "{}") =
        commClose(id, data, metadata)
    }
  }
}

object CommHandler {

  abstract class Comm {
    def message(data: String, metadata: String): Unit
    def close(data: String, metadata: String): Unit
  }

}
