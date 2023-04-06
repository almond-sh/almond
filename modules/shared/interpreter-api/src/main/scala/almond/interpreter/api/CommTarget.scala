package almond.interpreter.api

/** Receiver of messages from client code running in the browser.
  *
  * See [[CommHandler]] to register it.
  */
abstract class CommTarget {
  def open(id: String, data: Array[Byte]): Unit
  def message(id: String, data: Array[Byte]): Unit
  def close(id: String, data: Array[Byte]): Unit
}

object CommTarget {

  def apply(onMessage: (String, Array[Byte]) => Unit): CommTarget =
    new CommTarget {
      def open(id: String, data: Array[Byte]): Unit = {}
      def message(id: String, data: Array[Byte]) =
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
