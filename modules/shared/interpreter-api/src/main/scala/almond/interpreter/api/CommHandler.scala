package almond.interpreter.api

import java.util.UUID
import java.nio.charset.StandardCharsets

/** Kind of message broker between the Jupyter UI and the kernel.
  *
  * See https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#custom-messages.
  *
  * Typically available in the implicit scope of user code run via the kernel.
  *
  * Can be used by users to send messages to custom code running in the browser, and receive
  * messages from it.
  *
  * Registering a target with name `"target_name"` allows to receive messages from frontends. From
  * the Jupyter classic UI, one can send messages to this target via code like
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
  def commOpen(targetName: String, id: String, data: Array[Byte], metadata: Array[Byte]): Unit
  @throws(classOf[IllegalArgumentException])
  def commMessage(id: String, data: Array[Byte], metadata: Array[Byte]): Unit
  @throws(classOf[IllegalArgumentException])
  def commClose(id: String, data: Array[Byte], metadata: Array[Byte]): Unit

  final def receiver(
    name: String,
    onOpen: (String, Array[Byte]) => Unit = (_, _) => (),
    onClose: (String, Array[Byte]) => Unit = (_, _) => ()
  )(
    // scraps the id (first arg of onOpen / onClose)
    onMessage: Array[Byte] => Unit
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
    data: Array[Byte] = "{}".getBytes(StandardCharsets.UTF_8),
    metadata: Array[Byte] = "{}".getBytes(StandardCharsets.UTF_8),
    onMessage: (String, Array[Byte]) => Unit = (_, _) => (),
    onClose: (String, Array[Byte]) => Unit = (_, _) => ()
  ): Comm = {
    commOpen(targetName, id, data, metadata)
    val t = CommTarget(
      onMessage = (id, data) => onMessage(id, data),
      onOpen = (_, _) => (), // ignore since we open the comm from the kernel
      onClose = (id, data) => onClose(id, data)
    )
    registerCommId(id, t)
    new Comm {
      def message(data: Array[Byte], metadata: Array[Byte]) =
        commMessage(id, data, metadata)
      def close(data: Array[Byte], metadata: Array[Byte]) =
        commClose(id, data, metadata)
    }
  }
}

object CommHandler {

  abstract class Comm {
    def message(data: Array[Byte], metadata: Array[Byte]): Unit
    def close(data: Array[Byte], metadata: Array[Byte]): Unit
  }

}
