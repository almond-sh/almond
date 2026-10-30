package almond.interpreter.api

/** Receiver of messages from client code running in the browser.
  *
  * See [[CommHandler]] to register it.
  */
abstract class CommTarget {
  def open(id: String, data: Array[Byte]): Unit
  def message(id: String, data: Array[Byte]): Unit
  def close(id: String, data: Array[Byte]): Unit

  /** Called when a comm is opened, with the binary buffers of the message, if any
    *
    * Override this rather than the method without buffers to receive them. By default, this
    * discards the buffers and calls the method without them.
    */
  def open(id: String, data: Array[Byte], buffers: Seq[Array[Byte]]): Unit =
    open(id, data)

  /** Called when a comm message is received, with the binary buffers of the message, if any
    *
    * Override this rather than the method without buffers to receive them. By default, this
    * discards the buffers and calls the method without them.
    */
  def message(id: String, data: Array[Byte], buffers: Seq[Array[Byte]]): Unit =
    message(id, data)
}

object CommTarget {

  def apply(onMessage: (String, Array[Byte]) => Unit): CommTarget =
    new CommTarget {
      def open(id: String, data: Array[Byte]): Unit = {}
      def message(id: String, data: Array[Byte])    =
        onMessage(id, data)
      def close(id: String, data: Array[Byte]): Unit = {}
    }

  def apply(
    onMessage: (String, Array[Byte]) => Unit,
    onOpen: (String, Array[Byte]) => Unit,
    onClose: (String, Array[Byte]) => Unit
  ): CommTarget =
    new CommTarget {
      def open(id: String, data: Array[Byte]) =
        onOpen(id, data)
      def message(id: String, data: Array[Byte]) =
        onMessage(id, data)
      def close(id: String, data: Array[Byte]) =
        onClose(id, data)
    }
}
