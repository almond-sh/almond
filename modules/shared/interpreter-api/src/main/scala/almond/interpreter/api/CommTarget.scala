package almond.interpreter.api

/**
  * Receiver of messages from client code running in the browser.
  *
  * See [[CommHandler]] to register it.
  */
trait CommTarget {
  def open(id: String, data: String): Unit
  def message(id: String, data: String): Unit
  def close(id: String, data: String): Unit
}

object CommTarget {

  def apply(onMessage: (String, String) => Unit): CommTarget =
    new CommTarget {
      def open(id: String, data: String): Unit = {}
      def message(id: String, data: String) =
        onMessage(id, data)
      def close(id: String, data: String): Unit = {}
    }

  def apply(
    onMessage: (String, String) => Unit,
    onOpen: (String, String) => Unit,
    onClose: (String, String) => Unit
  ): CommTarget =
    new CommTarget {
      def open(id: String, data: String) =
        onOpen(id, data)
      def message(id: String, data: String) =
        onMessage(id, data)
      def close(id: String, data: String) =
        onClose(id, data)
    }
}
