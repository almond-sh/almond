package almond.channels

import java.nio.charset.StandardCharsets
import java.{util => ju}

import scala.util.Try

final case class Message(
  idents: Seq[Seq[Byte]],
  header: Array[Byte],
  parentHeader: Array[Byte],
  metadata: Array[Byte],
  content: Array[Byte]
) {
  override def toString: String = {
    val b = new StringBuilder("Message(")
    b.append(idents.toString)

    def byteArray(bytes: Array[Byte]): Unit = {
      b.append(", ")
      val s = Try(new String(bytes, StandardCharsets.UTF_8)).getOrElse(b.toString)
      b.append(s)
    }

    byteArray(header)
    byteArray(parentHeader)
    byteArray(metadata)
    byteArray(content)

    b.append(')')
    b.toString
  }

  override def equals(obj: Any): Boolean =
    obj match {
      case other: Message =>
        idents == other.idents &&
        ju.Arrays.equals(header, other.header) &&
        ju.Arrays.equals(parentHeader, other.parentHeader) &&
        ju.Arrays.equals(metadata, other.metadata) &&
        ju.Arrays.equals(content, other.content)
      case _ => false
    }

  override def hashCode: Int = {
    var code = 17 + "Message".##
    code = 37 * code + idents.##
    code = 37 * code + ju.Arrays.hashCode(header)
    code = 37 * code + ju.Arrays.hashCode(parentHeader)
    code = 37 * code + ju.Arrays.hashCode(metadata)
    code = 37 * code + ju.Arrays.hashCode(content)
    37 * code
  }
}
